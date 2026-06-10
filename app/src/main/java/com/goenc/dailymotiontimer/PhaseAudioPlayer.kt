package com.goenc.dailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

internal class PhaseAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioThread = HandlerThread("PhaseAudioPlayer").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
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
    private val soundIds = AudioCue.values().associateWith { cue ->
        soundPool.load(appContext, cue.resId, 1)
    }
    private val playbackCleanupDelayMillis = AudioCue.values().associateWith { cue ->
        resolvePlaybackCleanupDelayMillis(cue)
    }

    private val loadedCues = mutableSetOf<AudioCue>()
    private var announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME
    private var currentStreamId = 0
    private var pendingPlayback: PendingPlayback? = null
    private var pendingPlaybackCompleteRunnable: Runnable? = null
    @Volatile
    private var isReleased = false

    init {
        Log.i(
            TAG,
            "Initialized SoundPool cueSoundIds=$soundIds",
        )
    }

    fun play(phase: WalkingPhase, logEntryId: Long? = null) {
        playCue(
            cue = AudioCue.phaseStart(phase),
            logEntryId = logEntryId,
        )
    }

    fun playElapsedMilestone(phase: WalkingPhase, elapsedMinutes: Int) {
        val cue = AudioCue.elapsedMilestone(phase, elapsedMinutes) ?: run {
            Log.w(TAG, "Ignoring unsupported ${phase.name} elapsed milestone minutes=$elapsedMinutes")
            return
        }
        playCue(cue = cue, logEntryId = null)
    }

    private fun playCue(cue: AudioCue, logEntryId: Long?) {
        if (isReleased) {
            Log.w(TAG, "Ignoring ${cue.description} cue because audio player is released")
            return
        }
        audioHandler.post {
            val playRequestedElapsedRealtime = SystemClock.elapsedRealtime()
            if (logEntryId != null) {
                PhaseTransitionLogStore.markPlayRequested(
                    entryId = logEntryId,
                    playRequestedElapsedRealtime = playRequestedElapsedRealtime,
                )
            }
            Log.i(
                TAG,
                "Received ${cue.description} cue playback request " +
                    "at=${playRequestedElapsedRealtime} logEntryId=${logEntryId ?: "none"}",
            )
            pendingPlayback = PendingPlayback(cue = cue, logEntryId = logEntryId)
            stopCurrentStreamLocked(reason = "replace with ${cue.description}")
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
        val cue = soundIds.entries.firstOrNull { it.value == sampleId }?.key
        if (cue == null) {
            Log.w(TAG, "Ignored unknown sound load callback soundId=$sampleId status=$status")
            return
        }

        if (status == 0) {
            loadedCues += cue
            Log.i(TAG, "Loaded ${cue.description} cue soundId=$sampleId")
        } else {
            loadedCues -= cue
            Log.e(TAG, "Failed to load ${cue.description} cue soundId=$sampleId status=$status")
        }

        val queuedPlayback = pendingPlayback ?: return
        if (!isCueLoaded(queuedPlayback.cue) || currentStreamId != 0) {
            return
        }

        Log.i(TAG, "Retrying pending ${queuedPlayback.cue.description} cue after load completion")
        startPendingPlayback()
    }

    private fun startPendingPlayback() {
        if (isReleased) {
            return
        }

        val queuedPlayback = pendingPlayback ?: return
        val cue = queuedPlayback.cue
        if (!isCueLoaded(cue)) {
            Log.i(TAG, "Keeping ${cue.description} cue pending because sound is not loaded yet")
            return
        }

        val soundPoolPlayElapsedRealtime = SystemClock.elapsedRealtime()
        val streamId =
            soundPool.play(
                cue.soundId(),
                announcementVolume,
                announcementVolume,
                1,
                0,
                1f,
            )
        if (streamId == 0) {
            Log.w(TAG, "Failed to start ${cue.description} cue playback")
            this.pendingPlayback = null
            return
        }

        queuedPlayback.logEntryId?.let { entryId ->
            PhaseTransitionLogStore.markSoundPoolPlay(
                entryId = entryId,
                soundPoolPlayElapsedRealtime = soundPoolPlayElapsedRealtime,
            )
        }
        currentStreamId = streamId
        Log.i(
            TAG,
            "Started ${cue.description} cue playback streamId=$streamId at=$soundPoolPlayElapsedRealtime",
        )
        schedulePlaybackCompletionLocked(cue, streamId)
    }

    private fun schedulePlaybackCompletionLocked(cue: AudioCue, streamId: Int) {
        cancelPendingPlaybackCompleteLocked()
        val completionRunnable = Runnable {
            pendingPlaybackCompleteRunnable = null
            if (currentStreamId != streamId) {
                return@Runnable
            }
            currentStreamId = 0
            if (pendingPlayback?.cue == cue) {
                pendingPlayback = null
            }
            Log.i(TAG, "Completed ${cue.description} cue playback streamId=$streamId")
        }
        pendingPlaybackCompleteRunnable = completionRunnable
        audioHandler.postDelayed(completionRunnable, cue.playbackCleanupDelayMillis())
    }

    private fun cancelPendingPlaybackCompleteLocked() {
        pendingPlaybackCompleteRunnable?.let { audioHandler.removeCallbacks(it) }
        pendingPlaybackCompleteRunnable = null
    }

    private fun stopCurrentStreamLocked(reason: String) {
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
    }

    private fun stopInternal(reason: String) {
        val queuedPlayback = pendingPlayback
        this.pendingPlayback = null
        stopCurrentStreamLocked(reason = reason)
        Log.i(
            TAG,
            "Stopped cue processing cue=${queuedPlayback?.cue?.description ?: "none"} reason=$reason",
        )
    }

    private fun isCueLoaded(cue: AudioCue): Boolean {
        return cue in loadedCues
    }

    private fun AudioCue.soundId(): Int {
        return checkNotNull(soundIds[this]) { "Missing sound id for $description" }
    }

    private fun AudioCue.playbackCleanupDelayMillis(): Long {
        return checkNotNull(playbackCleanupDelayMillis[this]) { "Missing cleanup delay for $description" }
    }

    private fun resolvePlaybackCleanupDelayMillis(cue: AudioCue): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                appContext.resources.openRawResourceFd(cue.resId).use { descriptor ->
                    checkNotNull(descriptor) { "Missing raw resource for ${cue.description}" }
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
            Log.w(TAG, "Using fallback cleanup delay for ${cue.description} cue", error)
        }.getOrDefault(DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS)
    }

    private companion object {
        private const val TAG = "PhaseAudioPlayer"
        private const val DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS = 1500L
        private const val PLAYBACK_CLEANUP_GRACE_MILLIS = 200L
    }

    private data class PendingPlayback(
        val cue: AudioCue,
        val logEntryId: Long?,
    )

    private enum class AudioCue(
        val phase: WalkingPhase,
        val elapsedMinutes: Int?,
        val resId: Int,
        val description: String,
    ) {
        FastPhaseStart(
            phase = WalkingPhase.Fast,
            elapsedMinutes = null,
            resId = R.raw.fast_phase,
            description = "Fast phase start",
        ),
        SlowPhaseStart(
            phase = WalkingPhase.Slow,
            elapsedMinutes = null,
            resId = R.raw.slow_phase,
            description = "Slow phase start",
        ),
        FastOneMinuteElapsed(
            phase = WalkingPhase.Fast,
            elapsedMinutes = 1,
            resId = R.raw.fast_one_minute_elapsed,
            description = "Fast 1 minute elapsed",
        ),
        FastTwoMinutesElapsed(
            phase = WalkingPhase.Fast,
            elapsedMinutes = 2,
            resId = R.raw.fast_two_minutes_elapsed,
            description = "Fast 2 minutes elapsed",
        ),
        SlowOneMinuteElapsed(
            phase = WalkingPhase.Slow,
            elapsedMinutes = 1,
            resId = R.raw.slow_one_minute_elapsed,
            description = "Slow 1 minute elapsed",
        ),
        SlowTwoMinutesElapsed(
            phase = WalkingPhase.Slow,
            elapsedMinutes = 2,
            resId = R.raw.slow_two_minutes_elapsed,
            description = "Slow 2 minutes elapsed",
        );

        companion object {
            fun phaseStart(phase: WalkingPhase): AudioCue {
                return values().first { cue ->
                    cue.phase == phase && cue.elapsedMinutes == null
                }
            }

            fun elapsedMilestone(phase: WalkingPhase, elapsedMinutes: Int): AudioCue? {
                return values().firstOrNull { cue ->
                    cue.phase == phase && cue.elapsedMinutes == elapsedMinutes
                }
            }
        }
    }
}
