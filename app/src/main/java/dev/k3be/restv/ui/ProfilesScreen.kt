package dev.k3be.restv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.k3be.restv.data.PhoneInput
import dev.k3be.restv.data.db.ProfileEntity
import dev.k3be.restv.ui.components.ActionMenu
import dev.k3be.restv.ui.components.PhoneInputDialog
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.M
import dev.k3be.restv.ui.theme.T

/**
 * "Who's watching?": big round avatars, OK picks, hold OK to rename or remove.
 * Each profile has its own Continue watching, My List and history; channels are shared.
 */
@Composable
fun ProfilesScreen(
    profiles: List<ProfileEntity>,
    current: Long?,
    onPick: (ProfileEntity) -> Unit,
    onAdd: (String) -> Unit,
    onRename: (ProfileEntity, String) -> Unit,
    onDelete: (ProfileEntity) -> Unit,
) {
    var menuFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var naming by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(profiles.size) { runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt("Who's watching?", T.displayXl)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            val startIndex = profiles.indexOfFirst { it.id == current }.coerceAtLeast(0)
            profiles.forEachIndexed { i, p ->
                Avatar(
                    name = p.name, color = Color(p.color),
                    modifier = if (i == startIndex) Modifier.focusRequester(first) else Modifier,
                    onClick = { onPick(p) },
                    onLongClick = { menuFor = p },
                )
            }
            if (profiles.size < 6) AddTile { adding = true }
        }
        Spacer(Modifier.height(28.dp))
        Txt("Hold OK on a profile to rename or remove it", T.caption, color = C.inkFaint)
    }
    menuFor?.let { p ->
        ActionMenu(
            title = p.name,
            actions = buildList {
                add("Rename" to { menuFor = null; naming = p })
                if (!p.main) add("Remove profile and its history" to { menuFor = null; onDelete(p) })
            },
            onClose = { menuFor = null },
        )
    }
    naming?.let { p ->
        PhoneInputDialog(
            title = "Rename ${p.name}",
            fields = listOf(PhoneInput.Field("n", "Name", p.name)),
            onResult = { v -> v["n"]?.takeIf { it.isNotBlank() }?.let { onRename(p, it) } },
            onClose = { naming = null },
        )
    }
    if (adding) PhoneInputDialog(
        title = "New profile",
        fields = listOf(PhoneInput.Field("n", "Name", "")),
        onResult = { v -> onAdd(v["n"].orEmpty()) },
        onClose = { adding = false },
    )
}

@Composable
private fun Avatar(name: String, color: Color, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, M.focus(), label = "avatar")
    Column(
        modifier.width(120.dp).remoteClickable(source, onClick, onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))), CircleShape)
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initials = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            Txt(initials, T.displayXl.copy(fontSize = 40.sp), color = Color.White)
        }
        Txt(name, T.title.copy(textAlign = TextAlign.Center), color = if (focused) C.ink else C.inkMuted, maxLines = 1)
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, M.focus(), label = "add")
    Column(
        Modifier.width(120.dp).remoteClickable(source, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(112.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .background(if (focused) C.focus else Color.White.copy(alpha = 0.08f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Txt("+", T.displayXl, color = if (focused) C.focusInk else C.ink) }
        Txt("Add profile", T.title.copy(textAlign = TextAlign.Center), color = if (focused) C.ink else C.inkMuted)
    }
}
