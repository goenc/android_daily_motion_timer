package com.goenc.dailymotiontimer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.goenc.dailymotiontimer.ui.theme.WorkoutFlowTimerTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: TimerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkoutFlowTimerTheme {
                NotificationPermissionEffect()
                val uiState by viewModel.uiState.collectAsState()
                val displayState = rememberDisplayState(uiState)
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (isSettingsOpen) {
                        StartDelaySettingsScreen(
                            uiState = displayState,
                            modifier = Modifier.padding(innerPadding),
                            onStartDelayChange = viewModel::updateStartDelaySeconds,
                            onBackClick = { isSettingsOpen = false },
                        )
                    } else {
                        TimerScreen(
                            uiState = displayState,
                            modifier = Modifier.padding(innerPadding),
                            onStartPauseClick = {
                                if (displayState.isRunning) {
                                    viewModel.pause()
                                } else {
                                    viewModel.startOrResume()
                                }
                            },
                            onStopClick = viewModel::stop,
                            onFastPhaseDurationChange = viewModel::updateFastPhaseDurationSeconds,
                            onSlowPhaseDurationChange = viewModel::updateSlowPhaseDurationSeconds,
                            onSetCountChange = viewModel::updateSetCount,
                            onAnnouncementVolumeChange = viewModel::updateAnnouncementVolume,
                            onVibrationEnabledChange = viewModel::updateVibrationEnabled,
                            onOpenOverlaySettingsClick = ::openOverlaySettings,
                            onOpenSettingsClick = { isSettingsOpen = true },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppVisible(true)
    }

    override fun onStop() {
        viewModel.setAppVisible(false)
        super.onStop()
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }
}

@Composable
private fun rememberDisplayState(uiState: TimerUiState): TimerUiState {
    val displayState by produceState(
        initialValue = uiState.resolveAt(SystemClock.elapsedRealtime()),
        uiState,
    ) {
        value = uiState.resolveAt(SystemClock.elapsedRealtime())
        while (uiState.isRunning) {
            delay(UI_REFRESH_INTERVAL_MILLIS)
            value = uiState.resolveAt(SystemClock.elapsedRealtime())
        }
    }
    return displayState
}

@Composable
private fun TimerScreen(
    uiState: TimerUiState,
    modifier: Modifier = Modifier,
    onStartPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onFastPhaseDurationChange: (Int) -> Unit,
    onSlowPhaseDurationChange: (Int) -> Unit,
    onSetCountChange: (Int) -> Unit,
    onAnnouncementVolumeChange: (Float) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
    onOpenOverlaySettingsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = stringResource(
                    R.string.current_set_label,
                    uiState.currentSetNumber,
                    uiState.setCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenSettingsClick) {
            Text(text = stringResource(R.string.open_settings))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (uiState.isPreparingStart) {
                stringResource(R.string.pre_start_countdown_label, uiState.formattedPreStartRemainingTime)
            } else {
                uiState.currentPhase.label
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = uiState.formattedRemainingTime,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.elapsed_time_label, uiState.formattedElapsedTime),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(32.dp))
        PhaseDurationSlider(
            title = stringResource(R.string.fast_phase_duration_label),
            selectedDurationLabel = uiState.formattedFastPhaseDuration,
            selectedDurationSeconds = uiState.fastPhaseDurationSeconds,
            enabled = !uiState.isActive,
            onDurationChange = onFastPhaseDurationChange,
        )
        Spacer(modifier = Modifier.height(20.dp))
        PhaseDurationSlider(
            title = stringResource(R.string.slow_phase_duration_label),
            selectedDurationLabel = uiState.formattedSlowPhaseDuration,
            selectedDurationSeconds = uiState.slowPhaseDurationSeconds,
            enabled = !uiState.isActive,
            onDurationChange = onSlowPhaseDurationChange,
        )
        Spacer(modifier = Modifier.height(20.dp))
        SetCountSlider(
            title = stringResource(R.string.set_count_label),
            selectedSetCountLabel = uiState.formattedSetCount,
            selectedSetCount = uiState.setCount,
            enabled = !uiState.isActive,
            onSetCountChange = onSetCountChange,
        )
        Spacer(modifier = Modifier.height(20.dp))
        AnnouncementVolumeSlider(
            title = stringResource(R.string.announcement_volume_label),
            announcementVolume = uiState.announcementVolume,
            onVolumeChange = onAnnouncementVolumeChange,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.setting_summary,
                    stringResource(R.string.vibration_enabled_label),
                    stringResource(
                        if (uiState.isVibrationEnabled) {
                            R.string.setting_on
                        } else {
                            R.string.setting_off
                        },
                    ),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Switch(
                checked = uiState.isVibrationEnabled,
                onCheckedChange = onVibrationEnabledChange,
                enabled = !uiState.isActive,
            )
        }
        if (uiState.isActive && !Settings.canDrawOverlays(context)) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.overlay_permission_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenOverlaySettingsClick,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(text = stringResource(R.string.open_overlay_settings))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            Button(onClick = onStartPauseClick) {
                Text(
                    text = if (uiState.isRunning) {
                        stringResource(R.string.pause)
                    } else if (uiState.isPaused) {
                        stringResource(R.string.resume)
                    } else {
                        stringResource(R.string.start)
                    },
                )
            }
            Button(onClick = onStopClick) {
                Text(text = stringResource(R.string.stop))
            }
        }
    }
}

@Composable
private fun StartDelaySettingsScreen(
    uiState: TimerUiState,
    modifier: Modifier = Modifier,
    onStartDelayChange: (Int) -> Unit,
    onBackClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(24.dp))
        StartDelaySlider(
            title = stringResource(R.string.start_delay_label),
            selectedDelayLabel = uiState.formattedStartDelay,
            selectedDelaySeconds = uiState.startDelaySeconds,
            enabled = !uiState.isActive,
            onDelayChange = onStartDelayChange,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onBackClick) {
            Text(text = stringResource(R.string.back_to_timer))
        }
    }
}

@Composable
private fun SetCountSlider(
    title: String,
    selectedSetCountLabel: String,
    selectedSetCount: Int,
    enabled: Boolean,
    onSetCountChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, selectedSetCountLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = setCountSliderIndex(selectedSetCount).toFloat(),
            onValueChange = { sliderValue ->
                val optionIndex = sliderValue.roundToInt().coerceIn(
                    0,
                    SET_COUNT_OPTIONS.lastIndex,
                )
                onSetCountChange(SET_COUNT_OPTIONS[optionIndex])
            },
            valueRange = 0f..SET_COUNT_OPTIONS.lastIndex.toFloat(),
            steps = SET_COUNT_OPTIONS.size - 2,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StartDelaySlider(
    title: String,
    selectedDelayLabel: String,
    selectedDelaySeconds: Int,
    enabled: Boolean,
    onDelayChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, selectedDelayLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = startDelaySliderIndex(selectedDelaySeconds).toFloat(),
            onValueChange = { sliderValue ->
                val optionIndex = sliderValue.roundToInt().coerceIn(
                    0,
                    START_DELAY_OPTIONS_SECONDS.lastIndex,
                )
                onDelayChange(START_DELAY_OPTIONS_SECONDS[optionIndex])
            },
            valueRange = 0f..START_DELAY_OPTIONS_SECONDS.lastIndex.toFloat(),
            steps = START_DELAY_OPTIONS_SECONDS.size - 2,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhaseLogEntryView(entry: PhaseTransitionLogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${entry.phase.label} / ${entry.source}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "差分 ${formatLogDelay(entry.displayDelayMillis)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                "理論 ${entry.theoreticalTransitionElapsedRealtime}ms\n" +
                    "検知 ${entry.detectedElapsedRealtime}ms (${formatLogDelay(entry.detectedDelayMillis)})\n" +
                    "キュー ${entry.enqueuedElapsedRealtime}ms (${formatLogDelay(entry.enqueuedDelayMillis)})\n" +
                    "play ${formatLoggedTimestamp(entry.playRequestedElapsedRealtime, entry.playRequestedDelayMillis)}\n" +
                    "SoundPool ${formatLoggedTimestamp(entry.soundPoolPlayElapsedRealtime, entry.soundPoolPlayDelayMillis)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AnnouncementVolumeSlider(
    title: String,
    announcementVolume: Float,
    onVolumeChange: (Float) -> Unit,
) {
    val normalizedAnnouncementVolume = normalizeAnnouncementVolume(announcementVolume)
    val volumePercent = (normalizedAnnouncementVolume * 100f).roundToInt()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, stringResource(R.string.announcement_volume_value, volumePercent)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { sliderValue ->
                val clampedPercent = sliderValue.roundToInt().coerceIn(0, 100)
                onVolumeChange(clampedPercent / 100f)
            },
            valueRange = 0f..100f,
            steps = 99,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhaseDurationSlider(
    title: String,
    selectedDurationLabel: String,
    selectedDurationSeconds: Int,
    enabled: Boolean,
    onDurationChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, selectedDurationLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = durationSliderIndex(selectedDurationSeconds).toFloat(),
            onValueChange = { sliderValue ->
                val optionIndex = sliderValue.roundToInt().coerceIn(
                    0,
                    PHASE_DURATION_OPTIONS_SECONDS.lastIndex,
                )
                onDurationChange(PHASE_DURATION_OPTIONS_SECONDS[optionIndex])
            },
            valueRange = 0f..PHASE_DURATION_OPTIONS_SECONDS.lastIndex.toFloat(),
            steps = PHASE_DURATION_OPTIONS_SECONDS.size - 2,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TimerScreenPreview() {
    WorkoutFlowTimerTheme {
        TimerScreen(
            uiState = TimerUiState(),
            onStartPauseClick = {},
            onStopClick = {},
            onFastPhaseDurationChange = {},
            onSlowPhaseDurationChange = {},
            onSetCountChange = {},
            onAnnouncementVolumeChange = {},
            onVibrationEnabledChange = {},
            onOpenOverlaySettingsClick = {},
            onOpenSettingsClick = {},
        )
    }
}

private fun durationSliderIndex(durationSeconds: Int): Int {
    return PHASE_DURATION_OPTIONS_SECONDS.indexOf(normalizePhaseDurationSeconds(durationSeconds))
        .coerceAtLeast(0)
}

private fun setCountSliderIndex(setCount: Int): Int {
    return SET_COUNT_OPTIONS.indexOf(normalizeSetCount(setCount))
        .coerceAtLeast(0)
}

private fun startDelaySliderIndex(startDelaySeconds: Int): Int {
    return START_DELAY_OPTIONS_SECONDS.indexOf(normalizeStartDelaySeconds(startDelaySeconds))
        .coerceAtLeast(0)
}

private fun formatLoggedTimestamp(timestamp: Long?, deltaMillis: Long?): String {
    if (timestamp == null || deltaMillis == null) {
        return "-"
    }
    return "${timestamp}ms (${formatLogDelay(deltaMillis)})"
}

private fun formatLogDelay(delayMillis: Long): String {
    return if (delayMillis >= 0L) {
        "+${delayMillis}ms"
    } else {
        "${delayMillis}ms"
    }
}

private const val UI_REFRESH_INTERVAL_MILLIS = 1_000L
