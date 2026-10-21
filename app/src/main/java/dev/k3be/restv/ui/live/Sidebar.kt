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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    Home("Home", Icons.home),
    Search("Search", Icons.search),
    Live("Live TV", Icons.live),
    Movies("Movies", Icons.film),
    Series("Series", Icons.series),
    Matches("Matches", Icons.ball),
    Settings("Settings", Icons.settings),
}

/** Top navigation bar with each screen's content below it. */
@Composable
fun NavScaffold(active: SidebarItem, onSelect: (SidebarItem) -> Unit, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(active, onSelect)
        Row(Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp), content = content)
    }
}

/**
 * Lets Back move focus up to the top bar before it leaves a tab: the app keeps one of these and
 * every screen's top bar registers with it.
 */
class TopBarFocus {
    val requester = FocusRequester()
    var hasFocus by mutableStateOf(false)
}

val LocalTopBarFocus = staticCompositionLocalOf { TopBarFocus() }

@Composable
fun TopBar(active: SidebarItem, onSelect: (SidebarItem) -> Unit, autoHide: Boolean = false) {
    val bar = LocalTopBarFocus.current
    DisposableEffect(Unit) { onDispose { bar.hasFocus = false } }
    // On artwork screens the bar fades away while you browse and comes back when you go up to it.
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (!autoHide || bar.hasFocus) 1f else 0f, androidx.compose.animation.core.tween(120), label = "bar",
    )
    Row(
        Modifier.fillMaxWidth().padding(start = S.safeX, end = S.safeX, top = 16.dp)
            .graphicsLayer { this.alpha = alpha }
            .onFocusChanged { bar.hasFocus = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt("Restv", T.title, color = C.accent, modifier = Modifier.padding(end = S.s4))
        listOf(SidebarItem.Home, SidebarItem.Live, SidebarItem.Movies, SidebarItem.Series, SidebarItem.Matches, SidebarItem.Search).forEach { item ->
            NavItem(item, active == item, if (item == active) Modifier.focusRequester(bar.requester) else Modifier) { if (item != active) onSelect(item) }
        }
        Spacer(Modifier.weight(1f))
        NavItem(
            SidebarItem.Settings, active == SidebarItem.Settings,
            if (active == SidebarItem.Settings) Modifier.focusRequester(bar.requester) else Modifier,
        ) { if (active != SidebarItem.Settings) onSelect(SidebarItem.Settings) }
    }
}

@Composable
private fun NavItem(item: SidebarItem, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fg = when {
        focused -> C.focusInk
        active -> C.ink
        else -> C.inkMuted
    }
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> C.focus
                    active -> C.bg100
                    else -> C.bg000.copy(alpha = 0f)
                }
            )
            .then(modifier)
            .remoteClickable(source, onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val iconOnly = item == SidebarItem.Search || item == SidebarItem.Settings
            if (iconOnly) Icon(item.icon, if (active && !focused) C.accent else fg, 20.dp)
            else Txt(item.label, T.label.copy(fontSize = T.label.fontSize.times(1.1f)), color = if (active && !focused) C.accent else fg)
        }
    }
}
