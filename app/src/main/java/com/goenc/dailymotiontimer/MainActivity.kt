package com.goenc.dailymotiontimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
                val phaseLogs by PhaseTransitionLogStore.entriesFlow.collectAsState()
                val displayState = rememberDisplayState(uiState)
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TimerScreen(
                        uiState = displayState,
                        phaseLogs = phaseLogs,
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
                        onAnnouncementVolumeChange = viewModel::updateAnnouncementVolume,
                        onVibrationEnabledChange = viewModel::updateVibrationEnabled,
                    )
                }
            }
        }
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
    phaseLogs: List<PhaseTransitionLogEntry>,
    modifier: Modifier = Modifier,
    onStartPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onFastPhaseDurationChange: (Int) -> Unit,
    onSlowPhaseDurationChange: (Int) -> Unit,
    onAnnouncementVolumeChange: (Float) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
) {
    var isLogDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = uiState.currentPhase.label,
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
            Column {
                Text(
                    text = stringResource(R.string.vibration_enabled_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        if (uiState.isVibrationEnabled) {
                            R.string.setting_on
                        } else {
                            R.string.setting_off
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = uiState.isVibrationEnabled,
                onCheckedChange = onVibrationEnabledChange,
                enabled = !uiState.isActive,
            )
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
        Button(
            onClick = { isLogDialogVisible = true },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = "ログ表示")
        }
    }

    if (isLogDialogVisible) {
        PhaseLogsDialog(
            phaseLogs = phaseLogs,
            onDismissRequest = { isLogDialogVisible = false },
        )
    }
}

@Composable
private fun PhaseLogsDialog(
    phaseLogs: List<PhaseTransitionLogEntry>,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = "遅延ログ")
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState),
            ) {
                val visibleLogs = phaseLogs.take(MAX_VISIBLE_LOG_COUNT)
                if (visibleLogs.isEmpty()) {
                    Text(text = "ログはまだありません")
                } else {
                    visibleLogs.forEach { entry ->
                        PhaseLogEntryView(entry = entry)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text(text = "閉じる")
            }
        },
    )
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
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.announcement_volume_value, volumePercent),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
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
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = selectedDurationLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
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
            phaseLogs = emptyList(),
            onStartPauseClick = {},
            onStopClick = {},
            onFastPhaseDurationChange = {},
            onSlowPhaseDurationChange = {},
            onAnnouncementVolumeChange = {},
            onVibrationEnabledChange = {},
        )
    }
}

private fun durationSliderIndex(durationSeconds: Int): Int {
    return PHASE_DURATION_OPTIONS_SECONDS.indexOf(normalizePhaseDurationSeconds(durationSeconds))
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

private const val MAX_VISIBLE_LOG_COUNT = 30
private const val UI_REFRESH_INTERVAL_MILLIS = 1_000L
