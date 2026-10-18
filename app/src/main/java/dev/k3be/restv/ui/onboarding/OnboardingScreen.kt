package dev.k3be.restv.ui.onboarding

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.k3be.restv.SyncState
import dev.k3be.restv.ui.components.TvButton
import dev.k3be.restv.ui.components.Txt
import dev.k3be.restv.ui.theme.C
import dev.k3be.restv.ui.theme.S
import dev.k3be.restv.ui.theme.T

@Composable
fun OnboardingScreen(sync: SyncState, onConnect: (name: String, host: String, user: String, pass: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("Ahmed") }
    var host by rememberSaveable { mutableStateOf("http://tv.business-cdn-8k.com") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Row(
        Modifier.fillMaxSize().padding(horizontal = S.safeX, vertical = S.safeY),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.width(340.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(S.s3)) {
            Txt("Restv", T.displayXl)
            Txt("Add your Xtream login", T.title, color = C.inkMuted)
            Txt(
                "Use the same server, username and password as your other IPTV app. Everything stays on this TV.",
                T.body, color = C.inkMuted, maxLines = 4,
            )
            Spacer(Modifier.height(S.s4))
            when (sync) {
                is SyncState.Running -> Txt(sync.message, T.label, color = C.accent, maxLines = 2)
                is SyncState.Failed -> Txt(sync.message, T.label, color = C.danger, maxLines = 3)
                SyncState.Idle -> {}
            }
        }
        Column(Modifier.width(420.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(S.s3)) {
            TvTextField("Account name", name, { name = it }, Modifier.focusRequester(first))
            TvTextField("Server", host, { host = it }, keyboardType = KeyboardType.Uri)
            TvTextField("Username", user, { user = it })
            TvTextField("Password", pass, { pass = it }, password = true)
            Spacer(Modifier.height(S.s2))
            TvButton(
                text = if (sync is SyncState.Running) "Connecting…" else "Connect",
                primary = true,
                enabled = user.isNotBlank() && pass.isNotBlank() && host.isNotBlank() && sync !is SyncState.Running,
            ) { onConnect(name, host, user, pass) }
        }
    }
}

@Composable
fun TvTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(S.radiusMd)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, T.caption, color = C.inkMuted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = T.bodyLg,
            cursorBrush = SolidColor(C.accent),
            interactionSource = source,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
                        Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                        Key.DirectionCenter -> { keyboard?.show(); true }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (focused) C.bg300 else C.bg200, shape)
                        .then(if (focused) Modifier.border(2.dp, C.focus, shape) else Modifier)
                        .padding(horizontal = S.s4),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
    }
}
