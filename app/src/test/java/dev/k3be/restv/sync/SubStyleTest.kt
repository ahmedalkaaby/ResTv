package dev.k3be.restv.sync

import androidx.test.core.app.ApplicationProvider
import dev.k3be.restv.RestvApp
import dev.k3be.restv.data.SubStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubStyleTest {
    @Test fun roundTripsAndMigrates() {
        val s = SubStyle(size = 4, bold = false, color = 1, opacity = 3, background = true, outline = false, shadow = true)
        assertEquals(s, SubStyle.decode(s.encode()))
        assertEquals(null, SubStyle.decode("garbage"))
        // Yellow at 50 %.
        assertEquals(0x7FFFE14D, s.argb)
        val app = ApplicationProvider.getApplicationContext<RestvApp>()
        // Older size/box settings carry over the first time.
        app.prefs.setSubSize(2); app.prefs.setSubBox(true)
        assertEquals(SubStyle(size = 3, background = true), app.prefs.subStyle())
        app.prefs.setSubStyle(s)
        assertEquals(s, app.prefs.subStyle())
    }
}
