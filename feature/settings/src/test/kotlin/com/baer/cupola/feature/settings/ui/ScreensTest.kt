package com.baer.cupola.feature.settings.ui

import android.view.Surface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.baer.cupola.feature.settings.AppInfo
import com.baer.cupola.feature.settings.CityEntry
import com.baer.cupola.feature.settings.CreditsParser
import com.baer.cupola.feature.settings.Credits
import com.baer.cupola.feature.settings.CreditsRepository
import com.baer.cupola.feature.settings.CreditsViewModel
import com.baer.cupola.feature.settings.FakeCityRepository
import com.baer.cupola.feature.settings.FakeSettingsRepository
import com.baer.cupola.feature.settings.FakeTleRepository
import com.baer.cupola.feature.settings.GRAZ
import com.baer.cupola.feature.settings.HomeCitySearchViewModel
import com.baer.cupola.feature.settings.InfoSnapshot
import com.baer.cupola.feature.settings.InfoUiState
import com.baer.cupola.feature.settings.InfoViewModel
import com.baer.cupola.feature.settings.PreviewRenderer
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.Settings
import com.baer.cupola.feature.settings.SettingsUiState
import com.baer.cupola.feature.settings.SettingsViewModel
import com.baer.cupola.feature.settings.VIENNA
import com.baer.cupola.orbit.geo.GroundLabel
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class RecordingPreview : PreviewRenderer {
    val events = mutableListOf<String>()
    override fun attach(surface: Surface) { events += "attach" }
    override fun resize(width: Int, height: Int) { events += "resize" }
    override fun detach() { events += "detach" }
}

private fun snapshot(retired: Boolean, home: CityEntry? = null) = InfoSnapshot(
    instant = Instant.parse("2026-10-19T12:00:00Z"),
    groundLabel = GroundLabel.City("Vienna", "AT", 12.0),
    localTime = LocalTime.of(13, 0),
    utcOffsetHours = 1,
    sunlit = false,
    timeToDaylight = Duration.ofMinutes(23),
    orbitDataEpoch = Instant.parse("2026-10-18T11:12:47Z"),
    retired = retired,
    homeCity = home,
    nextPass = null,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun host(
        repo: FakeSettingsRepository = FakeSettingsRepository(),
        cities: FakeCityRepository = FakeCityRepository(listOf(VIENNA, GRAZ)),
        preview: PreviewRenderer = RecordingPreview(),
        creditsRepo: CreditsRepository = CreditsRepository { Credits(emptyList(), emptyList()) },
    ) {
        val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
        val settings = SettingsViewModel(repo, cities)
        val info = InfoViewModel(repo, FakeTleRepository(), cities, clock, Dispatchers.Default)
        val search = HomeCitySearchViewModel(repo, cities)
        val credits = CreditsViewModel(creditsRepo)
        rule.setContent {
            CupolaNavHost(settings, info, search, credits, preview, AppInfo("1.2.3"), onSetWallpaper = {}, onShare = {})
        }
    }

    @Test
    fun settingsToggleCallsTheActions() {
        var bloom: Boolean? = null
        var rest: RestDirection? = null
        val noop = {}
        val actions = SettingsActions({ rest = it }, {}, {}, { bloom = it }, {}, {}, {}, noop, noop, noop, noop, noop, noop, noop)
        rule.setContent { SettingsScreen(SettingsUiState(Settings(), null, true), InfoUiState(), RecordingPreview(), actions) }
        rule.onNodeWithText("Bloom").performScrollTo().performClick()
        rule.onNodeWithText("Straight down").performScrollTo().performClick()
        assertEquals(false, bloom)
        assertEquals(RestDirection.NADIR, rest)
    }

    @Test
    fun lookAroundSliderHasAnAccessibleName() {
        host()
        rule.onNodeWithTag(SettingsTags.LOOK_AROUND).assert(hasContentDescription("Look-around strength"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lookAroundSliderShowsKeyboardFocus() {
        val noop = {}
        val actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, noop, noop, noop, noop, noop, noop, noop)
        lateinit var root: View
        rule.setContent {
            root = LocalView.current
            SettingsScreen(SettingsUiState(Settings(), null, true), InfoUiState(), RecordingPreview(), actions)
        }
        val slider = rule.onNodeWithTag(SettingsTags.LOOK_AROUND).performScrollTo()
        // captureToImage needs JDK 21 inside Robolectric's ShadowUiAutomation; drawing the root view does not.
        fun sliderPixels(): IntArray {
            val b = slider.fetchSemanticsNode().boundsInRoot
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val w = b.width.toInt()
            val h = b.height.toInt()
            return IntArray(w * h).also { bitmap.getPixels(it, 0, w, b.left.toInt(), b.top.toInt(), w, h) }
        }
        val unfocused = sliderPixels()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).requestFocus()
        rule.waitForIdle()
        val focused = sliderPixels()
        assertFalse("focused slider looks the same as unfocused", unfocused.contentEquals(focused))
    }

    @Test
    fun toggleReachesTheRepositoryThroughTheViewModel() {
        val repo = FakeSettingsRepository()
        host(repo = repo)
        rule.onNodeWithText("Dust").performScrollTo().performClick()
        rule.waitUntil(5_000) { !runBlocking { repo.settings.first() }.dust }
    }

    @Test
    fun resetButtonRestoresDefaultsThroughTheViewModel() {
        val repo = FakeSettingsRepository(Settings(bloom = false, dust = false))
        host(repo = repo)
        rule.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { repo.settings.first() } == Settings() }
    }

    @Test
    fun previewSurfaceIsHostedOnTheSettingsScreen() {
        host()
        rule.onNodeWithTag(SettingsTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun homeCityRowOpensSearchAndShowsFakeResults() {
        host()
        rule.onNodeWithText("Not set").performScrollTo().performClick()
        rule.onNodeWithText("Search a city").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Vi")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Vienna").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Vienna").assertIsDisplayed()
    }

    @Test
    fun creditsListShowsEveryCreditsTsvRow() {
        val rows = CreditsParser.parseRows(File("../../app/src/main/assets/credits.tsv").readText())
        assertTrue(rows.size >= 7)
        rule.setContent { CupolaTheme { CreditsScreen(Credits(rows, emptyList())) } }
        for (row in rows) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(row.work))
            rule.onNodeWithText(row.work).assertIsDisplayed()
        }
    }

    @Test
    fun infoShowsTheRetiredNoteOnlyWhenFlagged() {
        rule.setContent { CupolaTheme { InfoScreen(InfoUiState(snapshot(retired = true))) } }
        rule.onNodeWithText("The ISS has been retired. The wallpaper shows its last known orbit.").assertIsDisplayed()
        rule.onNodeWithText("Near Vienna, AT (12 km)").assertIsDisplayed()
        rule.onNodeWithText("23 min").assertIsDisplayed()
    }

    @Test
    fun infoWithoutFlagHasNoRetiredNote() {
        rule.setContent { CupolaTheme { InfoScreen(InfoUiState(snapshot(retired = false))) } }
        rule.onAllNodesWithText("The ISS has been retired.", substring = true).assertCountEquals(0)
    }

    @Test
    fun openScreenSurvivesStateRestore() {
        val restore = StateRestorationTester(rule)
        val repo = FakeSettingsRepository()
        val cities = FakeCityRepository(listOf(VIENNA, GRAZ))
        val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
        val settings = SettingsViewModel(repo, cities)
        val info = InfoViewModel(repo, FakeTleRepository(), cities, clock, Dispatchers.Default)
        val search = HomeCitySearchViewModel(repo, cities)
        val credits = CreditsViewModel { Credits(emptyList(), emptyList()) }
        restore.setContent {
            CupolaNavHost(settings, info, search, credits, RecordingPreview(), AppInfo("1.2.3"), onSetWallpaper = {}, onShare = {})
        }
        rule.onNodeWithText("About and privacy").performScrollTo().performClick()
        rule.onNodeWithText("Version 1.2.3").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Version 1.2.3").assertIsDisplayed()
    }

    @Test
    fun aboutStatesPrivacyAndRealisticOrbit() {
        host()
        rule.onNodeWithText("About and privacy").performScrollTo().performClick()
        rule.onNodeWithText("Version 1.2.3").assertIsDisplayed()
        rule.onNodeWithText("No data collected, no network access.").assertIsDisplayed()
        rule.onNodeWithText("realistic ISS orbit", substring = true).assertIsDisplayed()
    }
}
