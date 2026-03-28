package com.goenc.dailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

internal class PhaseAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioThread = HandlerThread("PhaseAudioPlayer").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (!isReleased) {
            audioHandler.post { handleAudioFocusChange(focusChange) }
        }
    }
    private val soundPool =
        SoundPool.Builder()
            .setAudioAttributes(audioAttributes)
            .setMaxStreams(1)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    if (!isReleased) {
                        audioHandler.post { handleSoundLoaded(sampleId, status) }
                    }
                }
            }
    private val soundIdFast = soundPool.load(appContext, R.raw.fast_phase, 1)
    private val soundIdSlow = soundPool.load(appContext, R.raw.slow_phase, 1)
    private val fastPlaybackCleanupDelayMillis =
        resolvePlaybackCleanupDelayMillis(R.raw.fast_phase, WalkingPhase.Fast)
    private val slowPlaybackCleanupDelayMillis =
        resolvePlaybackCleanupDelayMillis(R.raw.slow_phase, WalkingPhase.Slow)

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFastLoaded = false
    private var isSlowLoaded = false
    private var announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME
    private var currentStreamId = 0
    private var pendingPhase: WalkingPhase? = null
    private var pendingPlaybackCompleteRunnable: Runnable? = null
    private var hasAudioFocus = false
    @Volatile
    private var isReleased = false

    init {
        Log.i(
            TAG,
            "Initialized SoundPool fastSoundId=$soundIdFast slowSoundId=$soundIdSlow",
        )
    }

    fun play(phase: WalkingPhase) {
        if (isReleased) {
            Log.w(TAG, "Ignoring ${phase.name} cue because audio player is released")
            return
        }
        audioHandler.post {
            Log.i(TAG, "Received ${phase.name} cue playback request")
            pendingPhase = phase
            stopCurrentStreamLocked(
                reason = "replace with ${phase.name}",
                shouldAbandonAudioFocus = false,
            )
            startPendingPlayback()
        }
    }

    fun setAnnouncementVolume(volume: Float) {
        if (isReleased) {
            return
        }
        audioHandler.post {
            announcementVolume = normalizeAnnouncementVolume(volume)
            if (currentStreamId != 0) {
                soundPool.setVolume(currentStreamId, announcementVolume, announcementVolume)
            }
        }
    }

    fun stop() {
        if (isReleased) {
            return
        }
        audioHandler.post {
            stopInternal(reason = "stop")
        }
    }

    fun release() {
        if (isReleased) {
            return
        }
        isReleased = true
        audioHandler.post {
            Log.i(TAG, "Releasing SoundPool audio player")
            stopInternal(reason = "release")
            runCatching {
                soundPool.release()
            }.onFailure { error ->
                Log.w(TAG, "Failed to release SoundPool", error)
            }
            audioThread.quitSafely()
        }
    }

    private fun handleSoundLoaded(sampleId: Int, status: Int) {
        when (sampleId) {
            soundIdFast -> {
                isFastLoaded = status == 0
                if (isFastLoaded) {
                    Log.i(TAG, "Loaded Fast cue soundId=$sampleId")
                } else {
                    Log.e(TAG, "Failed to load Fast cue soundId=$sampleId status=$status")
                }
            }

            soundIdSlow -> {
                isSlowLoaded = status == 0
                if (isSlowLoaded) {
                    Log.i(TAG, "Loaded Slow cue soundId=$sampleId")
                } else {
                    Log.e(TAG, "Failed to load Slow cue soundId=$sampleId status=$status")
                }
            }

            else -> Log.w(TAG, "Ignored unknown sound load callback soundId=$sampleId status=$status")
        }

        val phase = pendingPhase ?: return
        if (!isPhaseLoaded(phase) || currentStreamId != 0) {
            return
        }

        Log.i(TAG, "Retrying pending ${phase.name} cue after load completion")
        startPendingPlayback()
    }

    private fun startPendingPlayback() {
        if (isReleased) {
            return
        }

        val phase = pendingPhase ?: return
        if (!isPhaseLoaded(phase)) {
            Log.i(TAG, "Keeping ${phase.name} cue pending because sound is not loaded yet")
            return
        }

        val focusResult = requestAudioFocus()
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Skipping ${phase.name} cue because audio focus request failed result=$focusResult")
            pendingPhase = null
            return
        }

        val streamId =
            soundPool.play(
                phase.soundId(),
                announcementVolume,
                announcementVolume,
                1,
                0,
                1f,
            )
        if (streamId == 0) {
            Log.w(TAG, "Failed to start ${phase.name} cue playback")
            pendingPhase = null
            abandonAudioFocus()
            return
        }

        currentStreamId = streamId
        Log.i(TAG, "Started ${phase.name} cue playback streamId=$streamId")
        schedulePlaybackCompletionLocked(phase, streamId)
    }

    private fun schedulePlaybackCompletionLocked(phase: WalkingPhase, streamId: Int) {
        cancelPendingPlaybackCompleteLocked()
        val completionRunnable = Runnable {
            pendingPlaybackCompleteRunnable = null
            if (currentStreamId != streamId) {
                return@Runnable
            }
            currentStreamId = 0
            if (pendingPhase == phase) {
                pendingPhase = null
            }
            Log.i(TAG, "Completed ${phase.name} cue playback streamId=$streamId")
            abandonAudioFocus()
        }
        pendingPlaybackCompleteRunnable = completionRunnable
        audioHandler.postDelayed(completionRunnable, phase.playbackCleanupDelayMillis())
    }

    private fun requestAudioFocus(): Int {
        if (audioManager == null) {
            Log.w(TAG, "AudioManager was unavailable")
            hasAudioFocus = false
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        if (hasAudioFocus) {
            Log.i(TAG, "Audio focus already held")
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request =
                    audioFocusRequest
                        ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(audioAttributes)
                            .setWillPauseWhenDucked(true)
                            .setOnAudioFocusChangeListener(focusChangeListener)
                            .build()
                            .also { audioFocusRequest = it }
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
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED ->
                Log.i(TAG, "Audio focus request granted")

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED ->
                Log.w(TAG, "Audio focus request delayed")

            else ->
                Log.w(TAG, "Audio focus request failed result=$result")
        }
        return result
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                Log.i(TAG, "Audio focus regained")
                if (pendingPhase != null && currentStreamId == 0) {
                    startPendingPlayback()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                hasAudioFocus = false
                Log.w(
                    TAG,
                    "Audio focus lost transiently change=$focusChange pending=${pendingPhase?.name ?: "none"}",
                )
                stopCurrentStreamLocked(
                    reason = "audio focus transient loss",
                    shouldAbandonAudioFocus = false,
                )
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                Log.w(
                    TAG,
                    "Audio focus lost permanently pending=${pendingPhase?.name ?: "none"}",
                )
                stopCurrentStreamLocked(
                    reason = "audio focus loss",
                    shouldAbandonAudioFocus = false,
                )
                pendingPhase = null
                abandonAudioFocus()
            }
        }
    }

    private fun cancelPendingPlaybackCompleteLocked() {
        pendingPlaybackCompleteRunnable?.let { audioHandler.removeCallbacks(it) }
        pendingPlaybackCompleteRunnable = null
    }

    private fun stopCurrentStreamLocked(reason: String, shouldAbandonAudioFocus: Boolean) {
        cancelPendingPlaybackCompleteLocked()
        if (currentStreamId != 0) {
            runCatching {
                soundPool.stop(currentStreamId)
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop cue streamId=$currentStreamId", error)
            }
            Log.i(TAG, "Stopped cue playback streamId=$currentStreamId reason=$reason")
            currentStreamId = 0
        }
        if (shouldAbandonAudioFocus) {
            abandonAudioFocus()
        }
    }

    private fun stopInternal(reason: String) {
        val phase = pendingPhase
        pendingPhase = null
        stopCurrentStreamLocked(reason = reason, shouldAbandonAudioFocus = false)
        Log.i(TAG, "Stopped cue processing phase=${phase?.name ?: "none"} reason=$reason")
        abandonAudioFocus()
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
        Log.i(TAG, "Audio focus abandoned")
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun isPhaseLoaded(phase: WalkingPhase): Boolean {
        return when (phase) {
            WalkingPhase.Fast -> isFastLoaded
            WalkingPhase.Slow -> isSlowLoaded
        }
    }

    private fun WalkingPhase.soundId(): Int {
        return when (this) {
            WalkingPhase.Fast -> soundIdFast
            WalkingPhase.Slow -> soundIdSlow
        }
    }

    private fun WalkingPhase.playbackCleanupDelayMillis(): Long {
        return when (this) {
            WalkingPhase.Fast -> fastPlaybackCleanupDelayMillis
            WalkingPhase.Slow -> slowPlaybackCleanupDelayMillis
        }
    }

    private fun resolvePlaybackCleanupDelayMillis(resId: Int, phase: WalkingPhase): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                appContext.resources.openRawResourceFd(resId).use { descriptor ->
                    checkNotNull(descriptor) { "Missing raw resource for ${phase.name}" }
                    retriever.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.length,
                    )
                    val durationMillis =
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?: DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS
                    (durationMillis + PLAYBACK_CLEANUP_GRACE_MILLIS).coerceAtLeast(
                        DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS,
                    )
                }
            } finally {
                runCatching { retriever.release() }
            }
        }.onFailure { error ->
            Log.w(TAG, "Using fallback cleanup delay for ${phase.name} cue", error)
        }.getOrDefault(DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS)
    }

    private companion object {
        private const val TAG = "PhaseAudioPlayer"
        private const val DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS = 1500L
        private const val PLAYBACK_CLEANUP_GRACE_MILLIS = 200L
    }
}
