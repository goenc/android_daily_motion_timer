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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.goenc.dailymotiontimer.heartrate.HeartRateSettingsSection
import com.goenc.dailymotiontimer.heartrate.HeartRateStatus
import com.goenc.dailymotiontimer.heartrate.HeartRateAlertPhaseMode
import com.goenc.dailymotiontimer.heartrate.HeartRateUiState
import com.goenc.dailymotiontimer.ui.theme.WorkoutFlowTimerTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: TimerViewModel by viewModels()
    private var shouldFinishOnStop = false
    private var suppressFinishOnStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkoutFlowTimerTheme {
                NotificationPermissionEffect()
                val uiState by viewModel.uiState.collectAsState()
                val heartRateUiState by viewModel.heartRateUiState.collectAsState()
                val displayState = rememberDisplayState(uiState)
                var isSettingsScreenVisible by rememberSaveable { mutableStateOf(false) }
                val heartRatePermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    if (viewModel.hasHeartRatePermissions()) {
                        viewModel.startHeartRateScan()
                    } else {
                        viewModel.reportHeartRatePermissionDenied()
                    }
                }
                val startHeartRateScan = {
                    if (viewModel.hasHeartRatePermissions()) {
                        viewModel.startHeartRateScan()
                    } else {
                        heartRatePermissionLauncher.launch(viewModel.heartRatePermissions())
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    if (isSettingsScreenVisible) {
                        SettingsScreen(
                            uiState = displayState,
                            heartRateUiState = heartRateUiState,
                            modifier = Modifier.padding(innerPadding),
                            onFastPhaseDurationChange = viewModel::updateFastPhaseDurationSeconds,
                            onSlowPhaseDurationChange = viewModel::updateSlowPhaseDurationSeconds,
                            onAnnouncementVolumeChange = viewModel::updateAnnouncementVolume,
                            onBeepVolumeChange = viewModel::updateBeepVolume,
                            onVibrationEnabledChange = viewModel::updateVibrationEnabled,
                            onFastPhaseBeepPitchChange = viewModel::updateFastPhaseBeepPitchPreset,
                            onSlowPhaseBeepPitchChange = viewModel::updateSlowPhaseBeepPitchPreset,
                            onFastPhaseBeepIntervalChange = viewModel::updateFastPhaseBeepIntervalSeconds,
                            onSlowPhaseBeepIntervalChange = viewModel::updateSlowPhaseBeepIntervalSeconds,
                            onStartHeartRateScan = startHeartRateScan,
                            onReconnectSavedDevice = viewModel::connectSavedHeartRateDevice,
                            onConnectHeartRateDevice = viewModel::connectHeartRateDevice,
                            onDisconnectHeartRateDevice = viewModel::disconnectHeartRateDevice,
                            onForgetHeartRateDevice = viewModel::forgetHeartRateDevice,
                            onHeartRateSettingsChange = viewModel::updateHeartRateSettings,
                            onHeartRateAlertVolumeChange = viewModel::updateHeartRateAlertVolume,
                            onBackClick = {
                                viewModel.stopHeartRateScan()
                                isSettingsScreenVisible = false
                            },
                        )
                    } else {
                        TimerScreen(
                            uiState = displayState,
                            heartRateUiState = heartRateUiState,
                            modifier = Modifier.padding(innerPadding),
                            onStartPauseClick = {
                                if (displayState.isRunning) {
                                    viewModel.pause()
                                } else {
                                    viewModel.startOrResume()
                                }
                            },
                            onStopClick = viewModel::stop,
                            onOpenOverlaySettingsClick = ::openOverlaySettings,
                            onOpenSettingsClick = { isSettingsScreenVisible = true },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppVisible(true)
        viewModel.connectSavedHeartRateDevice()
    }

    override fun onResume() {
        super.onResume()
        shouldFinishOnStop = false
        suppressFinishOnStop = false
    }

    override fun onUserLeaveHint() {
        shouldFinishOnStop = !suppressFinishOnStop
        suppressFinishOnStop = false
        super.onUserLeaveHint()
    }

    override fun onStop() {
        viewModel.stopHeartRateScan()
        viewModel.setAppVisible(false)
        if (!viewModel.uiState.value.isRunning) {
            viewModel.disconnectHeartRateDevice()
        }
        super.onStop()
        if (shouldFinishOnStop && !isChangingConfigurations) {
            finishAndRemoveTask()
        }
    }

    private fun openOverlaySettings() {
        suppressFinishOnStop = true
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
private fun CenteredElapsedTimeText(
    elapsedSeconds: Int,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val elapsedTimeText = remember(elapsedSeconds) { formatElapsedDuration(elapsedSeconds) }
    Text(
        text = elapsedTimeText,
        fontSize = 64.sp,
        fontWeight = fontWeight,
        color = color,
        modifier = modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun TimerScreen(
    uiState: TimerUiState,
    heartRateUiState: HeartRateUiState,
    modifier: Modifier = Modifier,
    onStartPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onOpenOverlaySettingsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    val activeTextColor = if (uiState.isRunning) Color.Black else Color.Unspecified
    val screenBackgroundColor = when {
        uiState.isRunning -> Color(0xFFC8E6C9)
        uiState.isPaused -> Color(0xFFFFE0B2)
        else -> MaterialTheme.colorScheme.background
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(screenBackgroundColor)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSettingsClick) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings_24),
                    contentDescription = stringResource(R.string.settings_title),
                    tint = Color.Black,
                )
            }
        }
        Text(
            text = uiState.currentPhase.label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = activeTextColor,
        )
        Text(
            text = uiState.formattedRemainingTime,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = activeTextColor,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.elapsed_time_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = activeTextColor,
        )
        CenteredElapsedTimeText(
            elapsedSeconds = uiState.elapsedSeconds,
            fontWeight = FontWeight.Bold,
            color = activeTextColor,
        )
        Spacer(modifier = Modifier.height(20.dp))
        HeartRateStatus(
            state = heartRateUiState,
            textColor = activeTextColor,
        )
        Spacer(modifier = Modifier.height(32.dp))
        if (uiState.isActive && !Settings.canDrawOverlays(context)) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.overlay_permission_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = activeTextColor,
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = onStartPauseClick,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) {
                Text(
                    text = if (uiState.isRunning) {
                        stringResource(R.string.pause)
                    } else if (uiState.isPaused) {
                        stringResource(R.string.resume)
                    } else {
                        stringResource(R.string.start)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Button(
                onClick = onStopClick,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) {
                Text(
                    text = stringResource(R.string.stop),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    uiState: TimerUiState,
    heartRateUiState: HeartRateUiState,
    modifier: Modifier = Modifier,
    onFastPhaseDurationChange: (Int) -> Unit,
    onSlowPhaseDurationChange: (Int) -> Unit,
    onAnnouncementVolumeChange: (Float) -> Unit,
    onBeepVolumeChange: (Float) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
    onFastPhaseBeepPitchChange: (BeepPitchPreset) -> Unit,
    onSlowPhaseBeepPitchChange: (BeepPitchPreset) -> Unit,
    onFastPhaseBeepIntervalChange: (Float) -> Unit,
    onSlowPhaseBeepIntervalChange: (Float) -> Unit,
    onStartHeartRateScan: () -> Unit,
    onReconnectSavedDevice: () -> Unit,
    onConnectHeartRateDevice: (String) -> Unit,
    onDisconnectHeartRateDevice: () -> Unit,
    onForgetHeartRateDevice: () -> Unit,
    onHeartRateSettingsChange: (
        targetLowerBpm: Int,
        targetUpperBpm: Int,
        dangerThresholdBpm: Int,
        alertsEnabled: Boolean,
        confirmSeconds: Int,
        alertPhaseMode: HeartRateAlertPhaseMode,
    ) -> Unit,
    onHeartRateAlertVolumeChange: (Float) -> Unit,
    onBackClick: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.HeartRate) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = onBackClick) {
                Text(text = stringResource(R.string.settings_close))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SettingsTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(text = stringResource(tab.titleResId)) },
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        when (selectedTab) {
            SettingsTab.HeartRate -> {
                HeartRateSettingsSection(
                    state = heartRateUiState,
                    onStartScan = onStartHeartRateScan,
                    onReconnectSavedDevice = onReconnectSavedDevice,
                    onConnectDevice = onConnectHeartRateDevice,
                    onDisconnect = onDisconnectHeartRateDevice,
                    onForgetDevice = onForgetHeartRateDevice,
                    onSettingsChange = onHeartRateSettingsChange,
                    onAlertVolumeChange = onHeartRateAlertVolumeChange,
                )
            }

            SettingsTab.Timer -> {
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
                Spacer(modifier = Modifier.height(20.dp))
                BeepVolumeSlider(
                    title = stringResource(R.string.beep_volume_label),
                    beepVolume = uiState.beepVolume,
                    onVolumeChange = onBeepVolumeChange,
                )
                Spacer(modifier = Modifier.height(20.dp))
                BeepPitchSelector(
                    title = stringResource(R.string.fast_phase_beep_pitch_label),
                    selectedPitchLabel = uiState.formattedFastPhaseBeepPitch,
                    selectedPitchPreset = uiState.fastPhaseBeepPitchPreset,
                    enabled = !uiState.isActive,
                    textColor = Color.Unspecified,
                    onPitchChange = onFastPhaseBeepPitchChange,
                )
                Spacer(modifier = Modifier.height(20.dp))
                BeepPitchSelector(
                    title = stringResource(R.string.slow_phase_beep_pitch_label),
                    selectedPitchLabel = uiState.formattedSlowPhaseBeepPitch,
                    selectedPitchPreset = uiState.slowPhaseBeepPitchPreset,
                    enabled = !uiState.isActive,
                    textColor = Color.Unspecified,
                    onPitchChange = onSlowPhaseBeepPitchChange,
                )
                Spacer(modifier = Modifier.height(20.dp))
                BeepIntervalSlider(
                    title = stringResource(R.string.fast_phase_beep_interval_label),
                    selectedIntervalLabel = uiState.formattedFastPhaseBeepInterval,
                    selectedIntervalSeconds = uiState.fastPhaseBeepIntervalSeconds,
                    enabled = !uiState.isActive,
                    textColor = Color.Unspecified,
                    onIntervalChange = onFastPhaseBeepIntervalChange,
                )
                Spacer(modifier = Modifier.height(20.dp))
                BeepIntervalSlider(
                    title = stringResource(R.string.slow_phase_beep_interval_label),
                    selectedIntervalLabel = uiState.formattedSlowPhaseBeepInterval,
                    selectedIntervalSeconds = uiState.slowPhaseBeepIntervalSeconds,
                    enabled = !uiState.isActive,
                    textColor = Color.Unspecified,
                    onIntervalChange = onSlowPhaseBeepIntervalChange,
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
            }
        }
    }
}

private enum class SettingsTab(val titleResId: Int) {
    HeartRate(R.string.heart_rate_settings_title),
    Timer(R.string.settings_timer_tab),
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
    textColor: Color = Color.Unspecified,
    onVolumeChange: (Float) -> Unit,
) {
    val normalizedAnnouncementVolume = normalizeAnnouncementVolume(announcementVolume)
    val volumePercent = (normalizedAnnouncementVolume * 100f).roundToInt()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, stringResource(R.string.announcement_volume_value, volumePercent)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { sliderValue ->
                val clampedPercent = sliderValue.roundToInt().coerceIn(0, 200)
                onVolumeChange(clampedPercent / 100f)
            },
            valueRange = 0f..200f,
            steps = 199,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BeepVolumeSlider(
    title: String,
    beepVolume: Float,
    textColor: Color = Color.Unspecified,
    onVolumeChange: (Float) -> Unit,
) {
    val volumePercent = beepVolumeToDisplayPercent(beepVolume)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.setting_summary,
                title,
                stringResource(R.string.announcement_volume_value, volumePercent),
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { sliderValue ->
                val clampedPercent = sliderValue.roundToInt().coerceIn(0, 100)
                onVolumeChange(beepVolumeFromDisplayPercent(clampedPercent))
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
    textColor: Color = Color.Unspecified,
    sliderColors: SliderColors = SliderDefaults.colors(),
    onDurationChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, selectedDurationLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
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
            colors = sliderColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BeepIntervalSlider(
    title: String,
    selectedIntervalLabel: String,
    selectedIntervalSeconds: Float,
    enabled: Boolean,
    textColor: Color = Color.Unspecified,
    onIntervalChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, selectedIntervalLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Slider(
            value = beepIntervalSliderIndex(selectedIntervalSeconds).toFloat(),
            onValueChange = { sliderValue ->
                val optionIndex = sliderValue.roundToInt().coerceIn(
                    0,
                    BEEP_INTERVAL_OPTIONS_SECONDS.lastIndex,
                )
                onIntervalChange(BEEP_INTERVAL_OPTIONS_SECONDS[optionIndex])
            },
            valueRange = 0f..BEEP_INTERVAL_OPTIONS_SECONDS.lastIndex.toFloat(),
            steps = BEEP_INTERVAL_OPTIONS_SECONDS.size - 2,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BeepPitchSelector(
    title: String,
    selectedPitchLabel: String,
    selectedPitchPreset: BeepPitchPreset,
    enabled: Boolean,
    textColor: Color = Color.Unspecified,
    onPitchChange: (BeepPitchPreset) -> Unit,
) {
    val pitchOptions = listOf(
        BeepPitchPreset.Low,
        BeepPitchPreset.Mid,
        BeepPitchPreset.High,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.setting_summary, title, selectedPitchLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pitchOptions.forEach { option ->
                val isSelected = option == selectedPitchPreset
                val buttonModifier = Modifier.weight(1f)
                if (isSelected) {
                    Button(
                        onClick = { onPitchChange(option) },
                        enabled = enabled,
                        modifier = buttonModifier,
                    ) {
                        Text(text = option.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onPitchChange(option) },
                        enabled = enabled,
                        modifier = buttonModifier,
                    ) {
                        Text(text = option.label)
                    }
                }
            }
        }
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
            heartRateUiState = HeartRateUiState(),
            onStartPauseClick = {},
            onStopClick = {},
            onOpenOverlaySettingsClick = {},
            onOpenSettingsClick = {},
        )
    }
}

private fun durationSliderIndex(durationSeconds: Int): Int {
    return PHASE_DURATION_OPTIONS_SECONDS.indexOf(normalizePhaseDurationSeconds(durationSeconds))
        .coerceAtLeast(0)
}

private fun beepIntervalSliderIndex(intervalSeconds: Float): Int {
    val normalizedInterval = normalizeBeepIntervalSeconds(
        intervalSeconds,
        DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
    )
    return BEEP_INTERVAL_OPTIONS_SECONDS.indexOfFirst { option ->
        option == normalizedInterval
    }.coerceAtLeast(0)
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
