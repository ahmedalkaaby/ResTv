package dev.k3be.restv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.k3be.restv.data.AudioSource
import dev.k3be.restv.ui.components.FocusRow
import dev.k3be.restv.ui.components.Icon
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.SectionLabel
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T
import java.util.Locale

private val levels = listOf(0f to "Off", 0.25f to "Low", 0.5f to "Half")

@Composable
fun CommentaryPanel(
    sources: List<AudioSource>,
    active: AudioSource?,
    state: CommentaryState,
    delayMs: Long,
    channelLevel: Float,
    onPick: (AudioSource) -> Unit,
    onNudge: (Long) -> Unit,
    onChannelLevel: (Float) -> Unit,
    onOff: () -> Unit,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(active == null) { runCatching { first.requestFocus() } }

    Column(
        modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(C.bg100.copy(alpha = 0.97f))
            .padding(start = S.s6, end = S.safeX - S.s4, top = S.safeY, bottom = S.safeY),
        verticalArrangement = Arrangement.spacedBy(S.s2),
    ) {
        Txt("Commentary audio", T.title)

        if (active != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(S.radiusMd)).background(C.bg200).padding(S.s3),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt(active.name, T.label, color = C.accent)
                Txt("Audio held back by", T.caption, color = C.inkMuted)
                Txt(String.format(Locale.US, "%.1f s", delayMs / 1000f), T.display)
                val (line, color) = when (state) {
                    is CommentaryState.Filling -> "Starts in ${state.secondsLeft} s · collecting audio to match the picture" to C.accent
                    CommentaryState.Playing -> "Playing · match it to the picture with the buttons below" to C.success
                    is CommentaryState.Reconnecting -> "Reconnecting · try ${state.attempt}" to C.accent
                    is CommentaryState.Failed -> state.message to C.danger
                    CommentaryState.Off -> "" to C.inkMuted
                }
                Txt(line, T.caption, color = color, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TvButton("−1 s", modifier = Modifier.focusRequester(first)) { onNudge(-1_000) }
                TvButton("−0.1") { onNudge(-100) }
                TvButton("+0.1") { onNudge(100) }
                TvButton("+1 s") { onNudge(1_000) }
            }
            Txt("Commentary early (goal heard before seen)? Press +. Late? Press −.", T.caption, color = C.inkFaint, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Stadium", T.caption, color = C.inkMuted)
                levels.forEach { (v, label) ->
                    TvButton(label, primary = channelLevel == v) { onChannelLevel(v) }
                }
                TvButton("Turn off") { onOff() }
            }
        }

        SectionLabel(if (active == null) "Pick a source" else "Change source", Modifier.padding(top = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var prevGroup: String? = null
            sources.forEachIndexed { i, src ->
                if (src.group != prevGroup) {
                    item(key = "g-${src.group}-$i") {
                        Txt(src.group, T.caption, color = C.inkFaint, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                    }
                }
                prevGroup = src.group
                item(key = src.id) {
                    val isActive = src.id == active?.id
                    FocusRow(
                        modifier = if (active == null && i == 0) Modifier.focusRequester(first) else Modifier,
                        selected = isActive,
                        height = 40.dp,
                        onClick = { onPick(src) },
                    ) { _ ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.audio, if (isActive) C.accent else C.inkFaint, 16.dp)
                            Txt(src.name, T.label, color = if (isActive) C.accent else C.ink, modifier = Modifier.weight(1f))
                            if (src.lowUrl != null) Txt("+ low", T.caption, color = C.inkFaint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommentaryChip(name: String, state: CommentaryState, modifier: Modifier) {
    val text = when (state) {
        is CommentaryState.Filling -> "$name · starts in ${state.secondsLeft} s"
        CommentaryState.Playing -> name
        is CommentaryState.Reconnecting -> "$name · reconnecting"
        is CommentaryState.Failed -> "$name · not responding"
        CommentaryState.Off -> return
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(C.bg100.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.audio, if (state is CommentaryState.Failed) C.danger else C.accent, 14.dp)
        Txt(text, T.caption)
    }
}
