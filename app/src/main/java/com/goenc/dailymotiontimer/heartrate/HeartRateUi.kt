package com.goenc.dailymotiontimer.heartrate

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
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
    onReconnectSavedDevice: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onSettingsChange: (
        targetLowerBpm: Int,
        targetUpperBpm: Int,
        dangerThresholdBpm: Int,
        alertsEnabled: Boolean,
        normalReadingIntervalSeconds: Int,
        confirmSeconds: Int,
        alertPhaseMode: HeartRateAlertPhaseMode,
    ) -> Unit,
    onAlertVolumeChange: (Float) -> Unit,
    showVoiceAlertSwitch: Boolean = true,
    showAlertPhaseModeSelector: Boolean = true,
    showAlertToggleButtons: Boolean = false,
    showReadingIntervalSelector: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var targetLowerValue by remember(state.settings.targetLowerBpm) {
        mutableFloatStateOf(state.settings.targetLowerBpm.toFloat())
    }
    var targetUpperValue by remember(state.settings.targetUpperBpm) {
        mutableFloatStateOf(state.settings.targetUpperBpm.toFloat())
    }
    var dangerThresholdValue by remember(state.settings.dangerThresholdBpm) {
        mutableFloatStateOf(state.settings.dangerThresholdBpm.toFloat())
    }
    var confirmSecondsValue by remember(state.settings.confirmSeconds) {
        mutableFloatStateOf(state.settings.confirmSeconds.toFloat())
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.heart_rate_connection_state, state.connectionState.label),
            style = MaterialTheme.typography.bodyMedium,
        )
        state.savedDevice?.let { device ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.heart_rate_saved_device, device.name),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.batteryLevelPercent?.let {
                        stringResource(R.string.heart_rate_battery_level, it)
                    } ?: stringResource(R.string.heart_rate_battery_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.savedDevice == null) {
                Button(onClick = onStartScan) {
                    Text(stringResource(R.string.heart_rate_scan))
                }
            } else {
                if (
                    state.connectionState == HeartRateConnectionState.CONNECTED ||
                    state.connectionState == HeartRateConnectionState.CONNECTING
                ) {
                    OutlinedButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.heart_rate_disconnect))
                    }
                } else {
                    Button(onClick = onReconnectSavedDevice) {
                        Text(stringResource(R.string.heart_rate_reconnect))
                    }
                }
                OutlinedButton(onClick = onForgetDevice) {
                    Text(stringResource(R.string.heart_rate_forget))
                }
            }
        }
        if (state.savedDevice == null) {
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
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (device.supportsHeartRate) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = "${device.address} / ${device.rssi} dBm",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Button(onClick = { onConnectDevice(device.address) }) {
                        Text(stringResource(R.string.heart_rate_connect))
                    }
                }
            }
        }
        val normalizedTargetLower = targetLowerValue.roundToInt().coerceIn(
            MIN_HEART_RATE_THRESHOLD_BPM,
            MAX_HEART_RATE_THRESHOLD_BPM - 2,
        )
        val normalizedTargetUpper = targetUpperValue.roundToInt().coerceIn(
            normalizedTargetLower + 1,
            MAX_HEART_RATE_THRESHOLD_BPM - 1,
        )
        val normalizedDangerThreshold = dangerThresholdValue.roundToInt().coerceIn(
            normalizedTargetUpper + 1,
            MAX_HEART_RATE_THRESHOLD_BPM,
        )
        val normalizedConfirmSeconds = confirmSecondsValue.roundToInt().coerceIn(
            MIN_CONFIRM_SECONDS,
            MAX_CONFIRM_SECONDS,
        )
        Text(
            text = stringResource(R.string.heart_rate_target_lower, normalizedTargetLower),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = normalizedTargetLower.toFloat(),
            onValueChange = { value ->
                targetLowerValue = value.coerceIn(
                    MIN_HEART_RATE_THRESHOLD_BPM.toFloat(),
                    (normalizedTargetUpper - 1).toFloat(),
                )
            },
            onValueChangeFinished = {
                onSettingsChange(
                    normalizedTargetLower,
                    normalizedTargetUpper,
                    normalizedDangerThreshold,
                    state.settings.alertsEnabled,
                    state.settings.normalReadingIntervalSeconds,
                    normalizedConfirmSeconds,
                    state.settings.alertPhaseMode,
                )
            },
            valueRange = MIN_HEART_RATE_THRESHOLD_BPM.toFloat()..(MAX_HEART_RATE_THRESHOLD_BPM - 2).toFloat(),
            steps = MAX_HEART_RATE_THRESHOLD_BPM - MIN_HEART_RATE_THRESHOLD_BPM - 3,
        )
        Text(
            text = stringResource(R.string.heart_rate_target_upper, normalizedTargetUpper),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = normalizedTargetUpper.toFloat(),
            onValueChange = { value ->
                targetUpperValue = value.coerceIn(
                    (normalizedTargetLower + 1).toFloat(),
                    (normalizedDangerThreshold - 1).toFloat(),
                )
            },
            onValueChangeFinished = {
                onSettingsChange(
                    normalizedTargetLower,
                    normalizedTargetUpper,
                    normalizedDangerThreshold,
                    state.settings.alertsEnabled,
                    state.settings.normalReadingIntervalSeconds,
                    normalizedConfirmSeconds,
                    state.settings.alertPhaseMode,
                )
            },
            valueRange = (MIN_HEART_RATE_THRESHOLD_BPM + 1).toFloat()..(MAX_HEART_RATE_THRESHOLD_BPM - 1).toFloat(),
            steps = MAX_HEART_RATE_THRESHOLD_BPM - MIN_HEART_RATE_THRESHOLD_BPM - 3,
        )
        Text(
            text = stringResource(R.string.heart_rate_danger_threshold, normalizedDangerThreshold),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = normalizedDangerThreshold.toFloat(),
            onValueChange = { value ->
                dangerThresholdValue = value.coerceIn(
                    (normalizedTargetUpper + 1).toFloat(),
                    MAX_HEART_RATE_THRESHOLD_BPM.toFloat(),
                )
            },
            onValueChangeFinished = {
                onSettingsChange(
                    normalizedTargetLower,
                    normalizedTargetUpper,
                    normalizedDangerThreshold,
                    state.settings.alertsEnabled,
                    state.settings.normalReadingIntervalSeconds,
                    normalizedConfirmSeconds,
                    state.settings.alertPhaseMode,
                )
            },
            valueRange = (MIN_HEART_RATE_THRESHOLD_BPM + 2).toFloat()..MAX_HEART_RATE_THRESHOLD_BPM.toFloat(),
            steps = MAX_HEART_RATE_THRESHOLD_BPM - MIN_HEART_RATE_THRESHOLD_BPM - 3,
        )
        Text(
            text = stringResource(
                R.string.heart_rate_target_range,
                state.rule.targetLower,
                state.rule.targetUpper,
                state.rule.dangerThreshold,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (showVoiceAlertSwitch) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.heart_rate_voice_alert),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Switch(
                    checked = state.settings.alertsEnabled,
                    onCheckedChange = { enabled ->
                        onSettingsChange(
                            normalizedTargetLower,
                            normalizedTargetUpper,
                            normalizedDangerThreshold,
                            enabled,
                            state.settings.normalReadingIntervalSeconds,
                            normalizedConfirmSeconds,
                            state.settings.alertPhaseMode,
                        )
                    },
                )
            }
        }
        HeartRateAlertVolumeSlider(
            alertVolume = state.settings.alertVolume,
            onVolumeChange = onAlertVolumeChange,
        )
        if (showReadingIntervalSelector) {
            HeartRateReadingIntervalSelector(
                selectedIntervalSeconds = state.settings.normalReadingIntervalSeconds,
                onIntervalChange = { intervalSeconds ->
                    onSettingsChange(
                        normalizedTargetLower,
                        normalizedTargetUpper,
                        normalizedDangerThreshold,
                        state.settings.alertsEnabled,
                        intervalSeconds,
                        normalizedConfirmSeconds,
                        state.settings.alertPhaseMode,
                    )
                },
            )
        }
        Text(
            text = stringResource(R.string.heart_rate_alert_confirm_seconds, normalizedConfirmSeconds),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = normalizedConfirmSeconds.toFloat(),
            onValueChange = { value ->
                confirmSecondsValue = value.coerceIn(
                    MIN_CONFIRM_SECONDS.toFloat(),
                    MAX_CONFIRM_SECONDS.toFloat(),
                )
            },
            onValueChangeFinished = {
                onSettingsChange(
                    normalizedTargetLower,
                    normalizedTargetUpper,
                    normalizedDangerThreshold,
                    state.settings.alertsEnabled,
                    state.settings.normalReadingIntervalSeconds,
                    normalizedConfirmSeconds,
                    state.settings.alertPhaseMode,
                )
            },
            valueRange = MIN_CONFIRM_SECONDS.toFloat()..MAX_CONFIRM_SECONDS.toFloat(),
            steps = MAX_CONFIRM_SECONDS - MIN_CONFIRM_SECONDS - 1,
        )
        if (showAlertPhaseModeSelector) {
            Text(
                text = stringResource(R.string.heart_rate_alert_phase_mode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeartRateAlertPhaseMode.entries.forEach { mode ->
                    val isSelected = state.settings.alertPhaseMode == mode
                    val onClick = {
                        onSettingsChange(
                            normalizedTargetLower,
                            normalizedTargetUpper,
                            normalizedDangerThreshold,
                            state.settings.alertsEnabled,
                            state.settings.normalReadingIntervalSeconds,
                            normalizedConfirmSeconds,
                            mode,
                        )
                    }
                    if (isSelected) {
                        Button(
                            onClick = onClick,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            HeartRateAlertPhaseModeLabel(mode.label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onClick,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            HeartRateAlertPhaseModeLabel(mode.label)
                        }
                    }
                }
            }
        }
        if (showAlertToggleButtons) {
            Text(
                text = stringResource(R.string.heart_rate_reading_toggle_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val updateAlertsEnabled: (Boolean) -> Unit = { enabled ->
                    onSettingsChange(
                        normalizedTargetLower,
                        normalizedTargetUpper,
                        normalizedDangerThreshold,
                        enabled,
                        state.settings.normalReadingIntervalSeconds,
                        normalizedConfirmSeconds,
                        state.settings.alertPhaseMode,
                    )
                }
                if (state.settings.alertsEnabled) {
                    Button(
                        onClick = { updateAlertsEnabled(true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.heart_rate_reading_enabled))
                    }
                    OutlinedButton(
                        onClick = { updateAlertsEnabled(false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.heart_rate_reading_disabled))
                    }
                } else {
                    OutlinedButton(
                        onClick = { updateAlertsEnabled(true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.heart_rate_reading_enabled))
                    }
                    Button(
                        onClick = { updateAlertsEnabled(false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.heart_rate_reading_disabled))
                    }
                }
            }
        }
        state.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HeartRateAlertPhaseModeLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun HeartRateAlertVolumeSlider(
    alertVolume: Float,
    onVolumeChange: (Float) -> Unit,
) {
    val volumePercent = heartRateAlertVolumeToDisplayPercent(alertVolume)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.setting_summary,
                stringResource(R.string.heart_rate_alert_volume_label),
                stringResource(R.string.announcement_volume_value, volumePercent),
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { sliderValue ->
                val clampedPercent = sliderValue.roundToInt().coerceIn(0, 200)
                onVolumeChange(heartRateAlertVolumeFromDisplayPercent(clampedPercent))
            },
            valueRange = 0f..200f,
            steps = 199,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HeartRateReadingIntervalSelector(
    selectedIntervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.setting_summary,
                stringResource(R.string.heart_rate_reading_interval_label),
                formatHeartRateReadingInterval(selectedIntervalSeconds),
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = heartRateReadingIntervalSliderIndex(selectedIntervalSeconds).toFloat(),
            onValueChange = { sliderValue ->
                val optionIndex = sliderValue.roundToInt().coerceIn(
                    0,
                    HEART_RATE_READING_INTERVAL_OPTIONS_SECONDS.lastIndex,
                )
                onIntervalChange(HEART_RATE_READING_INTERVAL_OPTIONS_SECONDS[optionIndex])
            },
            valueRange = 0f..HEART_RATE_READING_INTERVAL_OPTIONS_SECONDS.lastIndex.toFloat(),
            steps = HEART_RATE_READING_INTERVAL_OPTIONS_SECONDS.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun heartRateReadingIntervalSliderIndex(intervalSeconds: Int): Int {
    return HEART_RATE_READING_INTERVAL_OPTIONS_SECONDS.indexOf(
        normalizeHeartRateReadingIntervalSeconds(intervalSeconds),
    ).coerceAtLeast(0)
}
