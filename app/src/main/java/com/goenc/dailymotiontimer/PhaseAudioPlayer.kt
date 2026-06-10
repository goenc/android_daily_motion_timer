package com.goenc.dailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class PhaseAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioThread = HandlerThread("PhaseAudioPlayer").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val cueAudioData = AudioCue.values().associateWith { cue ->
        WavAudioData.load(appContext, cue.resId)
    }

    private var announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME
    private var currentAudioTrack: AudioTrack? = null
    private var currentPlaybackId = 0
    private var pendingPlayback: PendingPlayback? = null
    private var pendingPlaybackCompleteRunnable: Runnable? = null
    @Volatile
    private var isReleased = false

    init {
        Log.i(TAG, "Initialized AudioTrack cueAudioData=${cueAudioData.keys}")
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
            stopCurrentPlaybackLocked(reason = "replace with ${cue.description}")
            startPendingPlayback()
        }
    }

    fun setAnnouncementVolume(volume: Float) {
        if (isReleased) {
            return
        }
        audioHandler.post {
            announcementVolume = normalizeAnnouncementVolume(volume)
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
            Log.i(TAG, "Releasing AudioTrack audio player")
            stopInternal(reason = "release")
            audioThread.quitSafely()
        }
    }

    private fun startPendingPlayback() {
        if (isReleased) {
            return
        }

        val queuedPlayback = pendingPlayback ?: return
        val cue = queuedPlayback.cue
        val audioData = checkNotNull(cueAudioData[cue]) { "Missing audio data for ${cue.description}" }
        val playbackVolume = announcementVolume
        val amplifiedPcm = audioData.scaledPcm16(playbackVolume)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioData.audioFormat())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(amplifiedPcm.size)
            .build()

        val bytesWritten = audioTrack.write(amplifiedPcm, 0, amplifiedPcm.size)
        if (bytesWritten != amplifiedPcm.size) {
            Log.w(
                TAG,
                "Failed to write full ${cue.description} cue bytes=$bytesWritten/${amplifiedPcm.size}",
            )
            audioTrack.release()
            pendingPlayback = null
            return
        }

        val playbackStartedElapsedRealtime = SystemClock.elapsedRealtime()
        audioTrack.play()
        queuedPlayback.logEntryId?.let { entryId ->
            PhaseTransitionLogStore.markSoundPoolPlay(
                entryId = entryId,
                soundPoolPlayElapsedRealtime = playbackStartedElapsedRealtime,
            )
        }
        currentAudioTrack = audioTrack
        currentPlaybackId += 1
        val playbackId = currentPlaybackId
        Log.i(
            TAG,
            "Started ${cue.description} cue playback playbackId=$playbackId " +
                "volume=$playbackVolume at=$playbackStartedElapsedRealtime",
        )
        schedulePlaybackCompletionLocked(cue, playbackId)
    }

    private fun schedulePlaybackCompletionLocked(cue: AudioCue, playbackId: Int) {
        cancelPendingPlaybackCompleteLocked()
        val completionRunnable = Runnable {
            pendingPlaybackCompleteRunnable = null
            if (currentPlaybackId != playbackId) {
                return@Runnable
            }
            releaseCurrentAudioTrackLocked()
            if (pendingPlayback?.cue == cue) {
                pendingPlayback = null
            }
            Log.i(TAG, "Completed ${cue.description} cue playback playbackId=$playbackId")
        }
        pendingPlaybackCompleteRunnable = completionRunnable
        audioHandler.postDelayed(completionRunnable, cue.playbackCleanupDelayMillis())
    }

    private fun cancelPendingPlaybackCompleteLocked() {
        pendingPlaybackCompleteRunnable?.let { audioHandler.removeCallbacks(it) }
        pendingPlaybackCompleteRunnable = null
    }

    private fun stopCurrentPlaybackLocked(reason: String) {
        cancelPendingPlaybackCompleteLocked()
        val stoppedPlaybackId = currentPlaybackId
        if (currentAudioTrack != null) {
            releaseCurrentAudioTrackLocked()
            Log.i(TAG, "Stopped cue playback playbackId=$stoppedPlaybackId reason=$reason")
        }
    }

    private fun releaseCurrentAudioTrackLocked() {
        currentAudioTrack?.let { audioTrack ->
            runCatching {
                audioTrack.stop()
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop cue playback", error)
            }
            runCatching {
                audioTrack.release()
            }.onFailure { error ->
                Log.w(TAG, "Failed to release cue playback", error)
            }
        }
        currentAudioTrack = null
    }

    private fun stopInternal(reason: String) {
        val queuedPlayback = pendingPlayback
        pendingPlayback = null
        stopCurrentPlaybackLocked(reason = reason)
        Log.i(
            TAG,
            "Stopped cue processing cue=${queuedPlayback?.cue?.description ?: "none"} reason=$reason",
        )
    }

    private fun AudioCue.playbackCleanupDelayMillis(): Long {
        val audioData = checkNotNull(cueAudioData[this]) { "Missing audio data for $description" }
        return (audioData.durationMillis + PLAYBACK_CLEANUP_GRACE_MILLIS)
            .coerceAtLeast(DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS)
    }

    private companion object {
        private const val TAG = "PhaseAudioPlayer"
        private const val DEFAULT_PLAYBACK_CLEANUP_DELAY_MILLIS = 1500L
        private const val PLAYBACK_CLEANUP_GRACE_MILLIS = 200L
        private const val PCM_FORMAT = 1
        private const val PCM_16_BIT = 16
    }

    private data class PendingPlayback(
        val cue: AudioCue,
        val logEntryId: Long?,
    )

    private data class WavAudioData(
        val sampleRate: Int,
        val channelCount: Int,
        val pcmData: ByteArray,
    ) {
        val durationMillis: Long
            get() = (frameCount * 1_000L) / sampleRate

        private val frameCount: Int
            get() = pcmData.size / (channelCount * Short.SIZE_BYTES)

        fun audioFormat(): AudioFormat {
            val channelMask = if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            return AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
        }

        fun scaledPcm16(volume: Float): ByteArray {
            val normalizedVolume = normalizeAnnouncementVolume(volume)
            val scaled = ByteArray(pcmData.size)
            var index = 0
            while (index < pcmData.size) {
                val sample =
                    (pcmData[index].toInt() and 0xFF) or
                        (pcmData[index + 1].toInt() shl 8)
                val clampedSample = (sample.toShort() * normalizedVolume)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                scaled[index] = (clampedSample.toInt() and 0xFF).toByte()
                scaled[index + 1] = ((clampedSample.toInt() shr 8) and 0xFF).toByte()
                index += Short.SIZE_BYTES
            }
            return scaled
        }

        companion object {
            fun load(context: Context, resId: Int): WavAudioData {
                val bytes = context.resources.openRawResource(resId).use { input ->
                    input.readBytes()
                }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                require(buffer.readAscii(4) == "RIFF") { "Unsupported wav: missing RIFF header" }
                buffer.int
                require(buffer.readAscii(4) == "WAVE") { "Unsupported wav: missing WAVE header" }

                var audioFormat = 0
                var channelCount = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var pcmData: ByteArray? = null

                while (buffer.remaining() >= 8) {
                    val chunkId = buffer.readAscii(4)
                    val chunkSize = buffer.int
                    val chunkStart = buffer.position()
                    when (chunkId) {
                        "fmt " -> {
                            audioFormat = buffer.short.toInt()
                            channelCount = buffer.short.toInt()
                            sampleRate = buffer.int
                            buffer.int
                            buffer.short
                            bitsPerSample = buffer.short.toInt()
                        }

                        "data" -> {
                            val data = ByteArray(chunkSize)
                            buffer.get(data)
                            pcmData = data
                        }
                    }

                    val nextChunkPosition = chunkStart + chunkSize + (chunkSize % 2)
                    buffer.position(nextChunkPosition.coerceAtMost(buffer.limit()))
                }

                require(audioFormat == PCM_FORMAT) { "Unsupported wav format: $audioFormat" }
                require(bitsPerSample == PCM_16_BIT) { "Unsupported wav bit depth: $bitsPerSample" }
                require(channelCount == 1 || channelCount == 2) {
                    "Unsupported wav channel count: $channelCount"
                }
                return WavAudioData(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    pcmData = checkNotNull(pcmData) { "Unsupported wav: missing data chunk" },
                )
            }

            private fun ByteBuffer.readAscii(length: Int): String {
                val bytes = ByteArray(length)
                get(bytes)
                return bytes.decodeToString()
            }
        }
    }

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
