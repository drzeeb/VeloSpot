package de.velospot.feature.map.presentation.sheets

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.velospot.R
import de.velospot.core.sensors.DiscoveredSensor
import de.velospot.core.sensors.SensorProfile
import de.velospot.core.sensors.SensorSnapshot
import de.velospot.feature.map.presentation.headingSemantics
import kotlinx.coroutines.flow.Flow

/**
 * Common wheel-circumference presets (metres) offered as quick-pick chips, used to
 * derive ground speed from a CSC wheel sensor. Riders can pick the tyre size that
 * matches their bike instead of measuring the rollout by hand.
 */
private val WHEEL_PRESETS_METERS = listOf(
    "700x25c" to 2.105,
    "700x28c" to 2.136,
    "700x32c" to 2.155,
    "26\"" to 2.070
)

/**
 * Sub-sheet: **Sensors** — pair external Bluetooth-LE speed/cadence, power and
 * heart-rate sensors. Scans while visible, lets the rider remember/forget devices
 * (auto-connected on the next ride), shows the live readings so a working sensor is
 * obvious, and offers a wheel-circumference picker for accurate CSC speed.
 *
 * On Android 12+ the runtime `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` permissions are
 * requested; below that no runtime Bluetooth permission is needed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SensorsSheet(
    snapshot: SensorSnapshot,
    rememberedAddresses: Set<String>,
    wheelCircumferenceMeters: Double,
    scan: () -> Flow<List<DiscoveredSensor>>,
    onRemember: (String) -> Unit,
    onForget: (String) -> Unit,
    onSetWheelCircumference: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    val context = LocalContext.current

    // On Android 12+ (API 31) scanning/connecting needs runtime BT permissions.
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
    }
    fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it } || result.isEmpty()
    }
    // Request the permissions once the sheet is shown (no-op below API 31).
    LaunchedEffect(Unit) {
        if (!granted && requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Scan only while the sheet is open AND we have the permission. produceState
    // cancels collection (and thus the scan) when the sheet leaves composition.
    val discovered by produceState(initialValue = emptyList<DiscoveredSensor>(), granted) {
        if (granted) scan().collect { value = it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.sensors_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).headingSemantics()
            )
            Text(
                text = stringResource(R.string.sensors_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            if (!granted && requiredPermissions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.sensors_permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(stringResource(R.string.sensors_permission_needed))
                }
            } else {
                // ── Live readings ────────────────────────────────────────────
                LiveReadingsRow(snapshot)

                Spacer(Modifier.height(12.dp))

                // ── Discovered sensors ───────────────────────────────────────
                SectionHeader(stringResource(R.string.sensors_scanning))
                if (discovered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sensors_none_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                } else {
                    discovered.forEach { sensor ->
                        DiscoveredSensorRow(
                            sensor = sensor,
                            remembered = sensor.address in rememberedAddresses,
                            onRemember = { onRemember(sensor.address) },
                            onForget = { onForget(sensor.address) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Wheel circumference (CSC speed) ──────────────────────────────
            SectionHeader(stringResource(R.string.sensors_wheel_size))
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WHEEL_PRESETS_METERS.forEach { (label, meters) ->
                    val selected = kotlin.math.abs(meters - wheelCircumferenceMeters) < 0.001
                    FilterChip(
                        selected = selected,
                        onClick = { onSetWheelCircumference(meters) },
                        label = { Text("$label · ${"%.3f".format(meters)} m") }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LiveReadingsRow(snapshot: SensorSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (snapshot.hasAnyReading) Icons.Default.Favorite else Icons.Default.Bluetooth,
            contentDescription = null,
            tint = if (snapshot.hasAnyReading) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
        val bpm = snapshot.heartRateBpm?.let { "$it ${stringResource(R.string.sensor_unit_bpm)}" }
        val watts = snapshot.powerWatts?.let { "$it ${stringResource(R.string.sensor_unit_watts)}" }
        val rpm = snapshot.cadenceRpm?.let { "$it ${stringResource(R.string.sensor_unit_rpm)}" }
        val speed = snapshot.speedMps?.let { de.velospot.core.format.formatRideSpeed(it) }
        val text = listOfNotNull(speed, bpm, watts, rpm).joinToString("   ")
            .ifBlank { stringResource(R.string.sensors_none_found) }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DiscoveredSensorRow(
    sensor: DiscoveredSensor,
    remembered: Boolean,
    onRemember: () -> Unit,
    onForget: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sensor.name ?: sensor.address,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Resolve the profile labels up front so the join runs outside a
            // @Composable lambda (stringResource can't be called inside one).
            val speedCadence = stringResource(R.string.sensor_profile_speed_cadence)
            val power = stringResource(R.string.hud_stat_power)
            val heart = stringResource(R.string.hud_stat_heart_rate)
            val profiles = sensor.profiles.joinToString(", ") { profile ->
                when (profile) {
                    SensorProfile.SPEED_CADENCE -> speedCadence
                    SensorProfile.POWER -> power
                    SensorProfile.HEART_RATE -> heart
                }
            }
            Text(
                text = profiles,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (remembered) {
            OutlinedButton(onClick = onForget) {
                Text(stringResource(R.string.sensors_forget))
            }
        } else {
            Button(onClick = onRemember) {
                Text(stringResource(R.string.sensors_remember))
            }
        }
    }
}


@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

