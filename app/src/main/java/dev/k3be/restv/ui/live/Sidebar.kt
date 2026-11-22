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
import dev.k3be.restv.ui.components.glass
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
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
    // A floating capsule over the content, Apple TV style; the wordmark sits beside it.
    Box(
        Modifier.fillMaxWidth().padding(start = S.safeX, end = S.safeX, top = 14.dp)
            .graphicsLayer { this.alpha = alpha }
            .onFocusChanged { bar.hasFocus = it.hasFocus },
    ) {
        Txt("Restv", T.titleLg.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = (-0.6).sp), color = C.ink, modifier = Modifier.align(Alignment.CenterStart))
        Row(
            Modifier.align(Alignment.Center)
                .glass(RoundedCornerShape(50), 1f, dev.k3be.restv.ui.components.Glass.Regular)
                .background(C.bg000.copy(alpha = 0.25f), RoundedCornerShape(50))
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(SidebarItem.Home, SidebarItem.Live, SidebarItem.Movies, SidebarItem.Series, SidebarItem.Matches, SidebarItem.Search, SidebarItem.Settings).forEach { item ->
                NavItem(item, active == item, if (item == active) Modifier.focusRequester(bar.requester) else Modifier) { if (item != active) onSelect(item) }
            }
        }
    }
}

@Composable
private fun NavItem(item: SidebarItem, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val barFocused = LocalTopBarFocus.current.hasFocus
    // The current tab is a white pill while you browse; on the bar itself, white follows focus.
    val solid = focused || (active && !barFocused)
    val fg = when {
        solid -> C.focusInk
        active -> C.ink
        else -> C.ink.copy(alpha = 0.78f)
    }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, dev.k3be.restv.ui.theme.M.focus(), label = "nav")
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    solid -> C.focus
                    active -> C.ink.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .then(modifier)
            .remoteClickable(source, onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val iconOnly = item == SidebarItem.Search || item == SidebarItem.Settings
        if (iconOnly) Icon(item.icon, fg, 19.dp)
        else Txt(item.label, T.label.copy(fontSize = T.label.fontSize.times(1.08f), fontWeight = if (solid || active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium), color = fg)
    }
}
