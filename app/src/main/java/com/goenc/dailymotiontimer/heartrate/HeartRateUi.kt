package com.goenc.dailymotiontimer.heartrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goenc.dailymotiontimer.R
import kotlin.math.roundToInt

@Composable
fun HeartRateStatus(
    state: HeartRateUiState,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (state.heartRate > 0) {
                stringResource(R.string.heart_rate_value, state.heartRate)
            } else {
                stringResource(R.string.heart_rate_unavailable)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Text(
            text = stringResource(
                R.string.heart_rate_status_detail,
                state.averageHeartRate?.toString() ?: "--",
                state.zone?.label ?: "--",
                state.connectionState.label,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
        state.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun HeartRateSettingsSection(
    state: HeartRateUiState,
    onStartScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onSettingsChange: (age: Int, alertsEnabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ageValue by remember(state.settings.age) { mutableFloatStateOf(state.settings.age.toFloat()) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.heart_rate_settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.heart_rate_connection_state, state.connectionState.label))
        state.savedDevice?.let { device ->
            Text(stringResource(R.string.heart_rate_saved_device, device.name), fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartScan) {
                Text(stringResource(R.string.heart_rate_scan))
            }
            if (
                state.connectionState == HeartRateConnectionState.CONNECTED ||
                state.connectionState == HeartRateConnectionState.CONNECTING
            ) {
                OutlinedButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.heart_rate_disconnect))
                }
            }
            if (state.savedDevice != null) {
                OutlinedButton(onClick = onForgetDevice) {
                    Text(stringResource(R.string.heart_rate_forget))
                }
            }
        }
        state.devices.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (device.supportsHeartRate) {
                            stringResource(R.string.heart_rate_supported_device, device.name)
                        } else {
                            device.name
                        },
                        fontWeight = if (device.supportsHeartRate) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text("${device.address} / ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onConnectDevice(device.address) }) {
                    Text(stringResource(R.string.heart_rate_connect))
                }
            }
        }
        Text(
            text = stringResource(R.string.heart_rate_age, ageValue.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = ageValue,
            onValueChange = { ageValue = it },
            onValueChangeFinished = {
                onSettingsChange(ageValue.roundToInt(), state.settings.alertsEnabled)
            },
            valueRange = 1f..120f,
            steps = 118,
        )
        Text(
            text = stringResource(
                R.string.heart_rate_target_range,
                state.rule.targetLower,
                state.rule.targetUpper,
                state.rule.dangerThreshold,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.heart_rate_voice_alert),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Switch(
                checked = state.settings.alertsEnabled,
                onCheckedChange = { enabled -> onSettingsChange(ageValue.roundToInt(), enabled) },
            )
        }
        state.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}
