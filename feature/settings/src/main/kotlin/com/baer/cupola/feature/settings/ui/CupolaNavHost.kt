package com.baer.cupola.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.baer.cupola.feature.settings.AppInfo
import com.baer.cupola.feature.settings.CreditsViewModel
import com.baer.cupola.feature.settings.HomeCitySearchViewModel
import com.baer.cupola.feature.settings.InfoViewModel
import com.baer.cupola.feature.settings.PreviewRenderer
import com.baer.cupola.feature.settings.SettingsViewModel

sealed interface Route {
    data object Settings : Route
    data object Info : Route
    data object HomeCity : Route
    data object Credits : Route
    data object About : Route
}

private val routes = listOf(Route.Settings, Route.Info, Route.HomeCity, Route.Credits, Route.About)

// Saved as indexes into [routes], so the open screen survives rotation and process restore.
private val BackStackSaver = listSaver<SnapshotStateList<Route>, Int>(
    save = { stack -> stack.map(routes::indexOf) },
    restore = { saved -> mutableStateListOf<Route>().apply { saved.mapTo(this, routes::get) } },
)

/** Screens and navigation (Navigation 3). The ViewModels are created by the caller (Hilt in the Activity). */
@Composable
fun CupolaNavHost(
    settings: SettingsViewModel,
    info: InfoViewModel,
    search: HomeCitySearchViewModel,
    credits: CreditsViewModel,
    preview: PreviewRenderer,
    appInfo: AppInfo,
    onSetWallpaper: () -> Unit,
    onShare: () -> Unit,
) {
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Route>(Route.Settings) }
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val infoState by info.uiState.collectAsStateWithLifecycle()
    val searchState by search.uiState.collectAsStateWithLifecycle()
    val creditsState by credits.credits.collectAsStateWithLifecycle()

    CupolaTheme {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<Route.Settings> {
                    SettingsScreen(
                        state = settingsState,
                        info = infoState,
                        preview = preview,
                        actions = SettingsActions(
                            onRestDirection = settings::setRestDirection,
                            onLookAroundStrength = settings::setLookAroundStrength,
                            onIdleFloat = settings::setIdleFloat,
                            onBloom = settings::setBloom,
                            onWindowGlare = settings::setWindowGlare,
                            onReflections = settings::setReflections,
                            onDust = settings::setDust,
                            onHomeCity = { backStack.add(Route.HomeCity) },
                            onInfo = { backStack.add(Route.Info) },
                            onCredits = { backStack.add(Route.Credits) },
                            onAbout = { backStack.add(Route.About) },
                            onSetWallpaper = onSetWallpaper,
                            onShare = onShare,
                            onReset = settings::resetToDefaults,
                        ),
                    )
                }
                entry<Route.Info> { InfoScreen(infoState) }
                entry<Route.HomeCity> {
                    HomeCityScreen(
                        state = searchState,
                        onQueryChange = search::onQueryChange,
                        onSelect = { city ->
                            search.select(city)
                            backStack.removeAt(backStack.lastIndex)
                        },
                        onClear = {
                            settings.clearHomeCity()
                            backStack.removeAt(backStack.lastIndex)
                        },
                    )
                }
                entry<Route.Credits> { CreditsScreen(creditsState) }
                entry<Route.About> { AboutScreen(appInfo, onCredits = { backStack.add(Route.Credits) }) }
            },
        )
    }
}
