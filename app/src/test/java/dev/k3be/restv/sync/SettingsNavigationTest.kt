package dev.k3be.restv.sync

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.RestvApp
import dev.k3be.restv.Session
import dev.k3be.restv.SyncState
import dev.k3be.restv.data.db.AccountEntity
import dev.k3be.restv.data.db.LoginEntity
import dev.k3be.restv.ui.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives Settings with the remote: Right must reach the pane, never the top bar. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xlarge-land-television")
class SettingsNavigationTest {
    @get:Rule val rule = createComposeRule()

    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun rightReachesCheckForUpdatesAndKeysCanBeTyped() {
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        app.prefs.setSubdlKey("")
        val session = Session(AccountEntity(1, "Me", 0), LoginEntity(id = 1, accountId = 1, host = "http://x", username = "u", password = "p"))
        rule.setContent { SettingsScreen(session, SyncState.Idle, {}, {}) }
        rule.waitForIdle()

        press(Key.DirectionRight)
        rule.onNode(hasAnyDescendant(hasText("Check for updates")).and(isFocused())).assertExists()

        // Back to the list, down to Arabic subtitles, then into it.
        press(Key.DirectionLeft)
        press(Key.DirectionDown) // Account & library
        press(Key.DirectionDown) // Arabic subtitles
        press(Key.DirectionRight)
        rule.onNode(hasAnyDescendant(hasText("Add")).and(isFocused())).assertExists()

        // OK on Add opens the field with focus in it, ready to type.
        press(Key.DirectionCenter)
        rule.onNode(hasSetTextAction().and(isFocused())).assertExists()
    }
}
