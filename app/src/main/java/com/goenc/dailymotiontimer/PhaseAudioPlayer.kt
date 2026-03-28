package com.goenc.dailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

internal class PhaseAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val stateLock = Any()
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        synchronized(stateLock) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasAudioFocus = true
                    if (pendingPhase != null) {
                        pendingPlaybackRetryCount = 0
                        cancelPendingPlaybackRetryLocked()
                        Log.i(TAG, "Audio focus regained, retrying queued phase cue")
                        startPendingPlayback()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                -> {
                    val interruptedPhase = currentPlaybackPhase ?: pendingPhase
                    if (interruptedPhase != null) {
                        pendingPhase = interruptedPhase
                        pendingPlaybackRetryCount = 0
                    }
                    hasAudioFocus = false
                    cancelPendingPlaybackRetryLocked()
                    Log.w(
                        TAG,
                        "Audio focus lost change=$focusChange phase=${interruptedPhase?.name ?: "unknown"}",
                    )
                    mediaPlayer?.let { player ->
                        stopPlayer(player, shouldAbandonAudioFocus = false)
                    }
                }
            }
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaPlayer: MediaPlayer? = null
    private var hasAudioFocus = false
    private var pendingPhase: WalkingPhase? = null
    private var currentPlaybackPhase: WalkingPhase? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var pendingPlaybackRetryRunnable: Runnable? = null
    private var pendingPlaybackRetryCount = 0

    fun play(phase: WalkingPhase) {
        synchronized(stateLock) {
            pendingPhase = phase
            pendingPlaybackRetryCount = 0
            cancelPendingPlaybackRetryLocked()
            mediaPlayer?.let { player ->
                stopPlayer(player, shouldAbandonAudioFocus = false)
            }
            startPendingPlayback()
        }
    }

    fun stop() {
        synchronized(stateLock) {
            pendingPhase = null
            pendingPlaybackRetryCount = 0
            cancelPendingPlaybackRetryLocked()
            currentPlaybackPhase = null
            mediaPlayer?.let { player ->
                stopPlayer(player, shouldAbandonAudioFocus = true)
            } ?: abandonAudioFocus()
        }
    }

    private fun startPendingPlayback() {
        val phase = pendingPhase ?: return
        cancelPendingPlaybackRetryLocked()
        val result = requestAudioFocus()
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            schedulePendingPlaybackRetryLocked(phase, result)
            return
        }

        val resId = phase.audioResId()
        val player = MediaPlayer()
        mediaPlayer = player
        currentPlaybackPhase = phase
        pendingPhase = null
        pendingPlaybackRetryCount = 0
        runCatching {
            appContext.resources.openRawResourceFd(resId).use { descriptor ->
                checkNotNull(descriptor) { "Missing raw resource for ${phase.name}" }
                player.setAudioAttributes(audioAttributes)
                player.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
            }
            player.setOnCompletionListener { completedPlayer ->
                synchronized(stateLock) {
                    Log.i(TAG, "Completed ${phase.name} cue playback")
                    releasePlayer(completedPlayer, shouldAbandonAudioFocus = true)
                }
            }
            player.setOnErrorListener { erroredPlayer, what, extra ->
                synchronized(stateLock) {
                    Log.e(
                        TAG,
                        "Failed to play ${phase.name} cue what=$what extra=$extra",
                    )
                    releasePlayer(erroredPlayer, shouldAbandonAudioFocus = true)
                }
                true
            }
            player.prepare()
            Log.i(TAG, "Starting ${phase.name} cue playback")
            player.start()
        }.onFailure { error ->
            Log.e(TAG, "Unable to start ${phase.name} cue playback", error)
            releasePlayer(player, shouldAbandonAudioFocus = true)
        }
    }

    private fun schedulePendingPlaybackRetryLocked(phase: WalkingPhase, result: Int) {
        if (pendingPhase != phase) {
            return
        }

        if (pendingPlaybackRetryCount >= MAX_PENDING_PLAYBACK_RETRY_COUNT) {
            Log.w(
                TAG,
                "Giving up queued ${phase.name} cue after $pendingPlaybackRetryCount retries result=$result",
            )
            pendingPhase = null
            pendingPlaybackRetryCount = 0
            cancelPendingPlaybackRetryLocked()
            return
        }

        pendingPlaybackRetryCount += 1
        Log.i(
            TAG,
            "Retrying ${phase.name} cue after focus result=$result attempt=$pendingPlaybackRetryCount",
        )
        val retryRunnable = Runnable {
            synchronized(stateLock) {
                if (pendingPhase != phase) {
                    pendingPlaybackRetryRunnable = null
                    return@synchronized
                }
                pendingPlaybackRetryRunnable = null
                startPendingPlayback()
            }
        }
        pendingPlaybackRetryRunnable = retryRunnable
        retryHandler.postDelayed(retryRunnable, PENDING_PLAYBACK_RETRY_INTERVAL_MILLIS)
    }

    private fun cancelPendingPlaybackRetryLocked() {
        pendingPlaybackRetryRunnable?.let { retryHandler.removeCallbacks(it) }
        pendingPlaybackRetryRunnable = null
    }

    private fun stopPlayer(player: MediaPlayer, shouldAbandonAudioFocus: Boolean) {
        runCatching {
            if (player.isPlaying) {
                player.stop()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to stop phase cue playback", error)
        }
        releasePlayer(player, shouldAbandonAudioFocus)
    }

    private fun releasePlayer(player: MediaPlayer, shouldAbandonAudioFocus: Boolean) {
        if (mediaPlayer === player) {
            mediaPlayer = null
        }
        if (currentPlaybackPhase != null) {
            currentPlaybackPhase = null
        }
        runCatching {
            player.reset()
        }
        runCatching {
            player.release()
        }
        if (shouldAbandonAudioFocus) {
            abandonAudioFocus()
        }
    }

    private fun requestAudioFocus(): Int {
        if (audioManager == null) {
            Log.w(TAG, "AudioManager was unavailable")
            hasAudioFocus = false
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        if (hasAudioFocus) {
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasAudioFocus) {
            Log.i(TAG, "Audio focus request granted")
        } else {
            Log.w(TAG, "Audio focus request was not granted result=$result")
        }
        return result
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) {
            audioFocusRequest = null
            hasAudioFocus = false
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun WalkingPhase.audioResId(): Int {
        return when (this) {
            WalkingPhase.Fast -> R.raw.fast_phase
            WalkingPhase.Slow -> R.raw.slow_phase
        }
    }

    private companion object {
        private const val TAG = "PhaseAudioPlayer"
        private const val MAX_PENDING_PLAYBACK_RETRY_COUNT = 10
        private const val PENDING_PLAYBACK_RETRY_INTERVAL_MILLIS = 300L
    }
}
