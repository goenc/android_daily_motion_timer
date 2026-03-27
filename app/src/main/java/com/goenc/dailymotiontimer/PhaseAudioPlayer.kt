package com.goenc.dailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log

internal class PhaseAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            stop()
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaPlayer: MediaPlayer? = null
    private var hasAudioFocus = false

    fun play(phase: WalkingPhase) {
        stop()
        val resId = phase.audioResId()
        val focusGranted = requestAudioFocus()
        if (!focusGranted) {
            Log.w(TAG, "Audio focus request was not granted for ${phase.name}")
        }

        val player = MediaPlayer()
        mediaPlayer = player
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
                Log.i(TAG, "Completed ${phase.name} cue playback")
                releasePlayer(completedPlayer)
            }
            player.setOnErrorListener { erroredPlayer, what, extra ->
                Log.e(
                    TAG,
                    "Failed to play ${phase.name} cue what=$what extra=$extra",
                )
                releasePlayer(erroredPlayer)
                true
            }
            player.prepare()
            Log.i(TAG, "Starting ${phase.name} cue playback")
            player.start()
        }.onFailure { error ->
            Log.e(TAG, "Unable to start ${phase.name} cue playback", error)
            releasePlayer(player)
        }
    }

    fun stop() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop phase cue playback", error)
            }
            releasePlayer(player)
        } ?: abandonAudioFocus()
    }

    private fun releasePlayer(player: MediaPlayer) {
        if (mediaPlayer === player) {
            mediaPlayer = null
        }
        runCatching {
            player.reset()
        }
        runCatching {
            player.release()
        }
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) {
            Log.w(TAG, "AudioManager was unavailable")
            return false
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
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
    }
}
