package com.goenc.dailymotiontimer

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.goenc.dailymotiontimer.ui.theme.WorkoutFlowTimerTheme
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: TimerViewModel by viewModels()

    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializeTextToSpeech()
        observeSpeechEvents()
        setContent {
            WorkoutFlowTimerTheme {
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
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTextToSpeechReady = false
        super.onDestroy()
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                isTextToSpeechReady = false
                return@TextToSpeech
            }
            val result = textToSpeech?.setLanguage(Locale.JAPANESE)
            isTextToSpeechReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun observeSpeechEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.speechEvents.collect { message ->
                    speakSafely(message)
                }
            }
        }
    }

    private fun speakSafely(message: String) {
        val tts = textToSpeech ?: return
        if (!isTextToSpeechReady) {
            return
        }
        runCatching {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "walking-phase")
        }
    }
}

@Composable
private fun TimerScreen(
    uiState: TimerUiState,
    modifier: Modifier = Modifier,
    onStartPauseClick: () -> Unit,
    onStopClick: () -> Unit,
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

@Preview(showBackground = true)
@Composable
private fun TimerScreenPreview() {
    WorkoutFlowTimerTheme {
        TimerScreen(
            uiState = TimerUiState(),
            onStartPauseClick = {},
            onStopClick = {},
        )
    }
}
