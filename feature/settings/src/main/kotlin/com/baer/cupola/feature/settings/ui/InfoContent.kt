package com.baer.cupola.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baer.cupola.feature.settings.InfoSnapshot
import com.baer.cupola.feature.settings.R
import com.baer.cupola.orbit.geo.GroundLabel
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
private val passFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Ground point, local time, daylight, orbit date, retired note; the next pass only when [compact] is false. */
@Composable
fun InfoContent(snapshot: InfoSnapshot?, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot == null) {
            Text(stringResource(R.string.info_loading))
            return@Column
        }
        if (snapshot.retired) {
            Text(stringResource(R.string.info_retired), color = MaterialTheme.colorScheme.tertiary)
        }
        val ground = when (val g = snapshot.groundLabel) {
            is GroundLabel.City -> stringResource(R.string.info_ground_city, g.name, g.countryCode, g.distanceKm.roundToInt())
            is GroundLabel.Sea -> stringResource(R.string.info_ground_sea, g.name)
        }
        InfoLine(stringResource(R.string.info_ground_point), ground)
        val offset = ZoneOffset.ofHours(snapshot.utcOffsetHours).id.let { if (it == "Z") "+0" else it }
        InfoLine(
            stringResource(R.string.info_local_time),
            stringResource(R.string.info_local_time_value, snapshot.localTime.format(timeFormat), offset),
        )
        val daylight = snapshot.timeToDaylight
        InfoLine(
            stringResource(R.string.info_daylight),
            when {
                daylight == null -> stringResource(R.string.info_daylight_unknown)
                daylight == Duration.ZERO -> stringResource(R.string.info_daylight_now)
                daylight.toHours() > 0 ->
                    stringResource(R.string.info_duration_hm, daylight.toHours(), daylight.toMinutesPart())
                else -> stringResource(R.string.info_duration_m, daylight.toMinutes().coerceAtLeast(1))
            },
        )
        InfoLine(
            stringResource(R.string.info_orbit_date),
            snapshot.orbitDataEpoch.atZone(ZoneOffset.UTC).format(dateFormat),
        )
        if (!compact) {
            val home = snapshot.homeCity
            if (home == null) {
                Text(stringResource(R.string.info_no_home))
            } else {
                val pass = snapshot.nextPass
                InfoLine(
                    stringResource(R.string.info_next_pass, home.city.name),
                    if (pass == null) {
                        stringResource(R.string.info_no_pass)
                    } else {
                        stringResource(
                            R.string.info_next_pass_value,
                            pass.start.atZone(ZoneId.systemDefault()).format(passFormat),
                            pass.maxElevationDeg.roundToInt(),
                        )
                    },
                )
            }
        }
    }
}
