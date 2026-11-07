package dev.k3be.restv.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Type on the phone instead of the remote: a tiny web page served by the TV on the home network,
 * reached by scanning a QR code. It runs only while the QR code is on screen, answers only on a
 * random one-time path, and hands the typed values back to the screen that opened it.
 */
class PhoneInput(
    private val title: String,
    private val fields: List<Field>,
    private val onSubmit: (Map<String, String>) -> Unit,
    /** For tests: the address to advertise instead of the home-network one. */
    private val hostOverride: String? = null,
) {
    data class Field(val id: String, val label: String, val value: String, val secret: Boolean = false, val multiline: Boolean = false)

    private val token = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** The address to put in the QR code, or null if the TV has no home-network address. */
    var url: String? = null
        private set

    fun start(): String? {
        val ip = hostOverride ?: lanAddress() ?: return null
        val socket = ServerSocket()
        socket.reuseAddress = true
        // A fixed port is easier to type if the QR code can't be scanned; fall back to any free one.
        runCatching { socket.bind(InetSocketAddress(8765)) }.onFailure { socket.bind(InetSocketAddress(0)) }
        server = socket
        running = true
        url = "http://$ip:${socket.localPort}/$token"
        thread(name = "phone-input", isDaemon = true) {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
        return url
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceAtMost(64 * 1024) ?: 0
        // Read the body by hand: readNBytes only exists on Android 13+.
        val bodyBytes = ByteArray(length)
        var got = 0
        while (got < length) { val n = input.read(bodyBytes, got, length - got); if (n < 0) break; got += n }
        val body = String(bodyBytes, 0, got, Charsets.UTF_8)
        val out = client.getOutputStream()
        fun respond(code: String, type: String, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 $code\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
        if (path.substringBefore('?') != "/$token") { respond("404 Not Found", "text/plain", "Not found"); return }
        when (method) {
            "GET" -> respond("200 OK", "text/html", page())
            "POST" -> {
                val values = parseForm(body)
                onSubmit(fields.associate { it.id to (values[it.id] ?: it.value) })
                respond("200 OK", "text/html", done())
            }
            else -> respond("405 Method Not Allowed", "text/plain", "")
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Restv · ${esc(title)}</title>
        <style>
          :root{color-scheme:dark}
          body{margin:0;font:16px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;background:#0b0b0f;color:#f2f2f5;
               min-height:100vh;display:flex;justify-content:center;
               background-image:radial-gradient(circle at 20% 0%,rgba(245,197,66,.12),transparent 55%)}
          main{width:100%;max-width:480px;padding:28px 20px}
          h1{font-size:22px;margin:0 0 4px} p{color:#a3a3ad;margin:0 0 22px;font-size:14px}
          label{display:block;font-size:13px;color:#a3a3ad;margin:16px 0 6px}
          input,textarea{width:100%;box-sizing:border-box;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.14);
               background:rgba(255,255,255,.06);color:#fff;font-size:17px;outline:none}
          input:focus,textarea:focus{border-color:#f5c542}
          textarea{min-height:90px}
          button{margin-top:24px;width:100%;padding:15px;border:0;border-radius:14px;background:#f5c542;color:#111;font-weight:700;font-size:17px}
          .b{font-size:12px;color:#6c6c76;margin-top:18px}
        </style></head><body><main>
        <h1>${esc(title)}</h1><p>Type here and press Send. It goes straight to your TV.</p>
        <form method="post">
        ${fields.joinToString("") { f ->
            "<label for=\"${f.id}\">${esc(f.label)}</label>" +
                if (f.multiline) "<textarea id=\"${f.id}\" name=\"${f.id}\" autocapitalize=\"off\" autocomplete=\"off\" spellcheck=\"false\">${esc(f.value)}</textarea>"
                else "<input id=\"${f.id}\" name=\"${f.id}\" type=\"text\" value=\"${esc(f.value)}\" autocapitalize=\"off\" autocomplete=\"off\" autocorrect=\"off\" spellcheck=\"false\">"
        }}
        <button type="submit">Send to TV</button></form>
        <div class="b">Only works on your home network while the code is on the TV.</div>
        </main></body></html>
    """.trimIndent()

    private fun done(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{margin:0;font:18px system-ui,sans-serif;background:#0b0b0f;color:#f2f2f5;display:flex;align-items:center;justify-content:center;height:100vh}</style>
        </head><body><div>✓ Sent to your TV. You can close this page.</div></body></html>
    """.trimIndent()

    companion object {
        fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.contains('=') }.associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        /** The TV's address on the home network (Wi-Fi or Ethernet). */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        /**
         * Pulls server, username and password out of a full playlist link such as
         * http://host:port/get.php?username=u&password=p&type=m3u — so the whole link can be pasted.
         */
        fun parseXtreamLink(link: String): Triple<String, String, String>? {
            val t = link.trim()
            val u = runCatching { java.net.URI(t) }.getOrNull() ?: return null
            val query = u.rawQuery ?: return null
            val q = parseForm(query)
            val user = q["username"] ?: return null
            val pass = q["password"] ?: return null
            val host = "${u.scheme ?: "http"}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
            return Triple(host, user, pass)
        }
    }
}
