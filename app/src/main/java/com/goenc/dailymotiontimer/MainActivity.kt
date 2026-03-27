package com.goenc.dailymotiontimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TimerScreen(
                        uiState = uiState,
                        modifier = Modifier.padding(innerPadding),
                        onStartPauseClick = {
                            if (uiState.isRunning) {
                                viewModel.pause()
                            } else {
                                viewModel.startOrResume()
                            }
                        },
                        onStopClick = viewModel::stop,
                        onFastPhaseDurationChange = viewModel::updateFastPhaseDurationSeconds,
                        onSlowPhaseDurationChange = viewModel::updateSlowPhaseDurationSeconds,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerScreen(
    uiState: TimerUiState,
    modifier: Modifier = Modifier,
    onStartPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onFastPhaseDurationChange: (Int) -> Unit,
    onSlowPhaseDurationChange: (Int) -> Unit,
) {
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
            onStartPauseClick = {},
            onStopClick = {},
            onFastPhaseDurationChange = {},
            onSlowPhaseDurationChange = {},
        )
    }
}

private fun durationSliderIndex(durationSeconds: Int): Int {
    return PHASE_DURATION_OPTIONS_SECONDS.indexOf(normalizePhaseDurationSeconds(durationSeconds))
        .coerceAtLeast(0)
}
