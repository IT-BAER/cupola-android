package com.baer.cupola.ui

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class SystemBarsTest {
    @Test
    fun barIconsStayLightOnTheDarkAppWhenTheSystemThemeIsLight() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.enableCupolaEdgeToEdge()
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertFalse("status bar icons", bars.isAppearanceLightStatusBars)
        assertFalse("navigation bar icons", bars.isAppearanceLightNavigationBars)
    }
}
