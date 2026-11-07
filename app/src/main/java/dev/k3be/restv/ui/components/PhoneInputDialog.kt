package dev.k3be.restv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.k3be.restv.data.PhoneInput
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

/** "Type on phone" button that opens [PhoneInputDialog]. */
@Composable
fun PhoneButton(modifier: Modifier = Modifier, label: String = "Type on phone", onClick: () -> Unit) {
    TvButton(label, icon = Icons.phone, modifier = modifier, onClick = onClick)
}

/**
 * Shows a QR code; scanning it on a phone on the same Wi-Fi opens a page with [fields]. What's sent
 * comes back through [onResult] and the dialog closes. Nothing leaves the home network.
 */
@Composable
fun PhoneInputDialog(
    title: String,
    fields: List<PhoneInput.Field>,
    onResult: (Map<String, String>) -> Unit,
    onClose: () -> Unit,
) {
    var received by remember { mutableStateOf<Map<String, String>?>(null) }
    val server = remember { PhoneInput(title, fields, onSubmit = { values -> received = values }) }
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        url = runCatching { server.start() }.getOrNull()
        failed = url == null
        onDispose { server.stop() }
    }
    LaunchedEffect(received) {
        val r = received ?: return@LaunchedEffect
        onResult(r)
        onClose()
    }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.background(C.bg000.copy(alpha = 0.75f), RoundedCornerShape(28.dp)).glass(RoundedCornerShape(28.dp), 1.4f).padding(S.s6),
            horizontalArrangement = Arrangement.spacedBy(S.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(232.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, "QR code", filterQuality = FilterQuality.None, modifier = Modifier.size(208.dp))
                else Txt(if (failed) "No network" else "…", T.label, color = Color.Black)
            }
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(S.s3)) {
                Txt(title, T.titleLg)
                if (failed) {
                    Txt("This TV isn't on a home network right now, so a phone can't reach it.", T.body, color = C.danger, maxLines = 3)
                } else {
                    Txt("Point your phone's camera at the code, type on the page that opens, and press Send. Your phone must be on the same Wi-Fi as the TV.", T.body, color = C.inkMuted, maxLines = 4)
                    Txt("Or open on the phone:", T.caption, color = C.inkFaint)
                    Txt(url ?: "", T.label, color = C.ink, maxLines = 2)
                    Txt("Waiting for your phone…", T.caption, color = C.accent)
                }
                TvButton("Close", modifier = Modifier.focusRequester(close), onClick = onClose)
            }
        }
    }
}

private fun qrBitmap(text: String): ImageBitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp.asImageBitmap()
}.getOrNull()
