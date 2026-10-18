package dev.k3be.restv.ui.live

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.k3be.restv.ui.components.Icon
import dev.k3be.restv.ui.components.Icons
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.components.remoteClickable
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

enum class SidebarItem(val label: String, val icon: ImageVector) {
    Live("Live TV", Icons.live),
    Settings("Settings", Icons.settings),
}

/** Collapsed icons while browsing; expands with labels when it has focus. */
@Composable
fun Sidebar(active: SidebarItem, onSelect: (SidebarItem) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 200.dp else 64.dp, tween(150), label = "sidebar")
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(if (expanded) C.bg100 else C.bg000)
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = S.safeY, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavItem(SidebarItem.Live, active == SidebarItem.Live, expanded) { onSelect(SidebarItem.Live) }
        Spacer(Modifier.weight(1f))
        NavItem(SidebarItem.Settings, active == SidebarItem.Settings, expanded) { onSelect(SidebarItem.Settings) }
    }
}

@Composable
private fun NavItem(item: SidebarItem, active: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fg = when {
        focused -> C.focusInk
        active -> C.accent
        else -> C.inkMuted
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(S.radiusMd))
            .background(if (focused) C.focus else C.bg000.copy(alpha = 0f))
            .remoteClickable(source, onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s3)) {
            Icon(item.icon, fg, 20.dp)
            if (expanded) Txt(item.label, T.label, color = fg)
        }
    }
}
