package com.baer.cupola.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baer.cupola.feature.settings.AppInfo
import com.baer.cupola.feature.settings.CityEntry
import com.baer.cupola.feature.settings.Credits
import com.baer.cupola.feature.settings.HomeCitySearchUiState
import com.baer.cupola.feature.settings.InfoUiState
import com.baer.cupola.feature.settings.R

@Composable
private fun ScreenColumn(title: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Opaque, so the previous screen does not show through during predictive back.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(modifier).safeDrawingPadding().padding(horizontal = 16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp))
        content()
    }
}

@Composable
fun InfoScreen(state: InfoUiState) {
    ScreenColumn(R.string.info_title, Modifier.verticalScroll(rememberScrollState())) {
        InfoContent(state.snapshot, compact = false)
    }
}

@Composable
fun HomeCityScreen(
    state: HomeCitySearchUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (CityEntry) -> Unit,
    onClear: () -> Unit,
) {
    ScreenColumn(R.string.home_city) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.home_city_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.home_city_clear)) }
        if (state.query.isNotBlank() && state.results.isEmpty()) Text(stringResource(R.string.home_city_no_results))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.results, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth().clickable { onSelect(entry) }.padding(vertical = 12.dp)) {
                    Text(entry.city.name, style = MaterialTheme.typography.bodyLarge)
                    val selected = if (entry.id == state.selectedId) " - " + stringResource(R.string.home_city_selected) else ""
                    Text(entry.city.countryCode + selected, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun CreditsScreen(credits: Credits?) {
    ScreenColumn(R.string.credits_title) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.about_nasa)) }
            item { Text(stringResource(R.string.credits_orbit_data)) }
            items(credits?.rows.orEmpty()) { row ->
                Column {
                    Text(row.work, style = MaterialTheme.typography.titleSmall)
                    Text(row.credit)
                    Text(row.licence, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(credits?.notices.orEmpty()) { notice ->
                Column {
                    Text(notice.title, style = MaterialTheme.typography.titleSmall)
                    Text(notice.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun AboutScreen(appInfo: AppInfo, onCredits: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ScreenColumn(R.string.about_title, Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.about_version, appInfo.versionName))
        Text(stringResource(R.string.about_orbit), Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.about_privacy), Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.about_nasa), Modifier.padding(top = 12.dp))
        TextButton(onClick = onCredits) { Text(stringResource(R.string.about_credits)) }
        TextButton(onClick = { uriHandler.openUri(PRIVACY_URL) }) { Text(stringResource(R.string.about_privacy_policy)) }
        TextButton(onClick = { uriHandler.openUri(SUPPORT_URL) }) { Text(stringResource(R.string.about_support)) }
    }
}

private const val PRIVACY_URL = "https://github.com/IT-BAER/cupola-android/blob/main/PRIVACY.md"
private const val SUPPORT_URL = "https://github.com/IT-BAER/cupola-android/issues"
