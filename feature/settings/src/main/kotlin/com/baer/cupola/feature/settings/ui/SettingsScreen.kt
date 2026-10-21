package com.baer.cupola.feature.settings.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baer.cupola.feature.settings.InfoUiState
import com.baer.cupola.feature.settings.PreviewRenderer
import com.baer.cupola.feature.settings.R
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.SettingsUiState

/** Everything the Settings screen can do; [SettingsViewModel] setters plus navigation and intents. */
data class SettingsActions(
    val onRestDirection: (RestDirection) -> Unit,
    val onLookAroundStrength: (Float) -> Unit,
    val onIdleFloat: (Boolean) -> Unit,
    val onBloom: (Boolean) -> Unit,
    val onWindowGlare: (Boolean) -> Unit,
    val onReflections: (Boolean) -> Unit,
    val onDust: (Boolean) -> Unit,
    val onHomeCity: () -> Unit,
    val onInfo: () -> Unit,
    val onCredits: () -> Unit,
    val onAbout: () -> Unit,
    val onSetWallpaper: () -> Unit,
    val onShare: () -> Unit,
    val onReset: () -> Unit,
)

object SettingsTags {
    const val LOOK_AROUND = "look_around_slider"
    const val PREVIEW = "preview"
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    info: InfoUiState,
    preview: PreviewRenderer,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    Column(modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
        PreviewSurface(preview, Modifier.fillMaxWidth().height(320.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onSetWallpaper) { Text(stringResource(R.string.set_wallpaper)) }
                OutlinedButton(onClick = actions.onShare) { Text(stringResource(R.string.share)) }
            }

            Section(R.string.rest_direction)
            Column(Modifier.selectableGroup()) {
                RadioRow(R.string.rest_diagonal, s.restDirection == RestDirection.DIAGONAL) {
                    actions.onRestDirection(RestDirection.DIAGONAL)
                }
                RadioRow(R.string.rest_nadir, s.restDirection == RestDirection.NADIR) {
                    actions.onRestDirection(RestDirection.NADIR)
                }
            }

            Section(R.string.look_around_strength)
            val lookAroundLabel = stringResource(R.string.look_around_strength)
            Slider(
                value = s.lookAroundStrength,
                onValueChange = actions.onLookAroundStrength,
                valueRange = 0f..1f,
                modifier = Modifier.testTag(SettingsTags.LOOK_AROUND).semantics { contentDescription = lookAroundLabel },
            )
            SwitchRow(R.string.idle_float, s.idleFloat, actions.onIdleFloat)

            Section(R.string.effects)
            SwitchRow(R.string.effect_bloom, s.bloom, actions.onBloom)
            SwitchRow(R.string.effect_glare, s.windowGlare, actions.onWindowGlare)
            SwitchRow(R.string.effect_reflections, s.reflections, actions.onReflections)
            SwitchRow(R.string.effect_dust, s.dust, actions.onDust)
            TextButton(onClick = actions.onReset) { Text(stringResource(R.string.reset_defaults)) }

            Section(R.string.home_city)
            Column(Modifier.fillMaxWidth().clickable(onClick = actions.onHomeCity).padding(vertical = 12.dp)) {
                Text(state.homeCity?.city?.name ?: stringResource(R.string.home_city_none))
            }

            HorizontalDivider()
            Section(R.string.info_title)
            InfoContent(info.snapshot, compact = true)
            TextButton(onClick = actions.onInfo) { Text(stringResource(R.string.info_open)) }

            HorizontalDivider()
            Row {
                TextButton(onClick = actions.onCredits) { Text(stringResource(R.string.nav_credits)) }
                TextButton(onClick = actions.onAbout) { Text(stringResource(R.string.nav_about)) }
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(label))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RadioRow(label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PreviewSurface(preview: PreviewRenderer, modifier: Modifier) {
    val description = stringResource(R.string.preview_description)
    var callback: SurfaceHolder.Callback? = null
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = preview.attach(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        preview.resize(width, height)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = preview.detach()
                }
                holder.addCallback(callback)
            }
        },
        modifier = modifier.testTag(SettingsTags.PREVIEW).semantics { contentDescription = description },
        onRelease = { view -> callback?.let { view.holder.removeCallback(it) } },
    )
}
