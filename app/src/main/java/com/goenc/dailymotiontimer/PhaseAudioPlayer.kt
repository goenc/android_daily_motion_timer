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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private val beepAudioData = BeepPitchPreset.entries.associateWith { preset ->
        WavAudioData.generateBeep(frequencyHz = preset.frequencyHz)
    }

    private var announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME
    private var beepVolume = DEFAULT_BEEP_VOLUME
    private var currentAudioTrack: AudioTrack? = null
    private var currentPlaybackId = 0
    private var currentPlayback: ActivePlayback? = null
    private var pendingBeepPlayback: PendingPlayback? = null
    private var pendingPlaybackCompleteRunnable: Runnable? = null
    @Volatile
    private var isReleased = false

    init {
        Log.i(TAG, "Initialized AudioTrack cueAudioData=${cueAudioData.keys}")
    }

    fun play(
        phase: WalkingPhase,
        logEntryId: Long? = null,
        onCompleted: (() -> Unit)? = null,
    ) {
        playSpeech(
            cue = AudioCue.phaseStart(phase),
            logEntryId = logEntryId,
            onCompleted = onCompleted,
        )
    }

    fun playElapsedMilestone(
        phase: WalkingPhase,
        elapsedMinutes: Int,
        onCompleted: (() -> Unit)? = null,
    ) {
        val cue = AudioCue.elapsedMilestone(phase, elapsedMinutes) ?: run {
            Log.w(TAG, "Ignoring unsupported ${phase.name} elapsed milestone minutes=$elapsedMinutes")
            return
        }
        playSpeech(cue = cue, logEntryId = null, onCompleted = onCompleted)
    }

    fun playBeep(pitchPreset: BeepPitchPreset) {
        if (isReleased) {
            Log.w(TAG, "Ignoring Phase beep cue because audio player is released")
            return
        }
        audioHandler.post {
            val audioData = checkNotNull(beepAudioData[pitchPreset]) {
                "Missing audio data for beep pitch ${pitchPreset.name}"
            }
            queueBeepPlayback(
                PendingPlayback(
                    audioData = audioData,
                    description = "Phase beep ${pitchPreset.name}",
                    kind = PlaybackKind.BEEP,
                    volume = beepVolume,
                    beepPitchPreset = pitchPreset,
                    logEntryId = null,
                    phaseStart = null,
                    onCompleted = null,
                ),
            )
        }
    }

    private fun playSpeech(
        cue: AudioCue,
        logEntryId: Long?,
        onCompleted: (() -> Unit)?,
    ) {
        if (isReleased) {
            Log.w(TAG, "Ignoring ${cue.description} cue because audio player is released")
            return
        }
        audioHandler.post {
            val request = PendingPlayback(
                audioData = checkNotNull(cueAudioData[cue]) { "Missing audio data for ${cue.description}" },
                description = cue.description,
                kind = PlaybackKind.SPEECH,
                volume = announcementVolume,
                beepPitchPreset = null,
                logEntryId = logEntryId,
                phaseStart = if (cue.elapsedMinutes == null) cue.phase else null,
                onCompleted = onCompleted,
            )
            val playRequestedElapsedRealtime = SystemClock.elapsedRealtime()
            if (logEntryId != null) {
                PhaseTransitionLogStore.markPlayRequested(
                    entryId = logEntryId,
                    playRequestedElapsedRealtime = playRequestedElapsedRealtime,
                )
            }
            Log.i(
                TAG,
                "Received ${request.description} cue playback request " +
                    "at=${playRequestedElapsedRealtime} logEntryId=${logEntryId ?: "none"}",
            )
            playSpeechRequest(request)
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

    fun setBeepVolume(volume: Float) {
        if (isReleased) {
            return
        }
        audioHandler.post {
            beepVolume = normalizeBeepVolume(volume)
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

    private fun playSpeechRequest(request: PendingPlayback) {
        if (currentPlayback?.request?.kind == PlaybackKind.BEEP) {
            stopCurrentPlaybackLocked(reason = "replace with ${request.description}")
        } else if (currentPlayback?.request?.kind == PlaybackKind.SPEECH) {
            stopCurrentPlaybackLocked(reason = "replace with ${request.description}")
        }
        startPlaybackLocked(request)
    }

    private fun queueBeepPlayback(request: PendingPlayback) {
        pendingBeepPlayback = request
        if (currentPlayback == null) {
            drainQueuedPlayback()
        }
    }

    private fun startPlaybackLocked(request: PendingPlayback) {
        if (isReleased) {
            return
        }

        val audioData = request.audioData
        val playbackVolume = request.volume
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
                "Failed to write full ${request.description} cue bytes=$bytesWritten/${amplifiedPcm.size}",
            )
            audioTrack.release()
            if (request.kind == PlaybackKind.BEEP && pendingBeepPlayback == request) {
                pendingBeepPlayback = null
            }
            return
        }

        val playbackStartedElapsedRealtime = SystemClock.elapsedRealtime()
        audioTrack.play()
        request.logEntryId?.let { entryId ->
            PhaseTransitionLogStore.markSoundPoolPlay(
                entryId = entryId,
                soundPoolPlayElapsedRealtime = playbackStartedElapsedRealtime,
            )
        }
        currentAudioTrack = audioTrack
        currentPlaybackId += 1
        val playbackId = currentPlaybackId
        currentPlayback = ActivePlayback(
            request = request,
            playbackId = playbackId,
        )
        if (request.kind == PlaybackKind.BEEP && pendingBeepPlayback == request) {
            pendingBeepPlayback = null
        }
        Log.i(
            TAG,
            "Started ${request.description} cue playback playbackId=$playbackId " +
                "volume=$playbackVolume at=$playbackStartedElapsedRealtime",
        )
        if (request.phaseStart != null) {
            Log.i(
                TAG,
                "Phase start speech started phase=${request.phaseStart.name} " +
                    "playbackId=$playbackId at=$playbackStartedElapsedRealtime",
            )
        }
        schedulePlaybackCompletionLocked(request, playbackId)
    }

    private fun schedulePlaybackCompletionLocked(request: PendingPlayback, playbackId: Int) {
        cancelPendingPlaybackCompleteLocked()
        val cleanupDelayMillis = request.playbackCleanupDelayMillis()
        Log.i(
            TAG,
            "Scheduled ${request.description} cue completion playbackId=$playbackId " +
                "kind=${request.kind} durationMillis=${request.audioData.durationMillis} " +
                "cleanupDelayMillis=$cleanupDelayMillis",
        )
        val completionRunnable = Runnable {
            pendingPlaybackCompleteRunnable = null
            if (currentPlaybackId != playbackId) {
                return@Runnable
            }
            releaseCurrentAudioTrackLocked(
                stopBeforeRelease = false,
                releaseReason = "normal completion",
                playbackId = playbackId,
                description = request.description,
            )
            if (currentPlayback?.playbackId == playbackId) {
                currentPlayback = null
            }
            val completedElapsedRealtime = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "Completed ${request.description} cue playback playbackId=$playbackId " +
                    "at=$completedElapsedRealtime",
            )
            if (request.phaseStart != null) {
                Log.i(
                    TAG,
                    "Phase start speech completed phase=${request.phaseStart.name} " +
                        "playbackId=$playbackId at=$completedElapsedRealtime",
                )
            }
            request.onCompleted?.invoke()
            drainQueuedPlayback()
        }
        pendingPlaybackCompleteRunnable = completionRunnable
        audioHandler.postDelayed(completionRunnable, cleanupDelayMillis)
    }

    private fun cancelPendingPlaybackCompleteLocked() {
        pendingPlaybackCompleteRunnable?.let { audioHandler.removeCallbacks(it) }
        pendingPlaybackCompleteRunnable = null
    }

    private fun stopCurrentPlaybackLocked(reason: String) {
        cancelPendingPlaybackCompleteLocked()
        val stoppedPlaybackId = currentPlaybackId
        if (currentAudioTrack != null) {
            releaseCurrentAudioTrackLocked(
                stopBeforeRelease = true,
                releaseReason = reason,
                playbackId = stoppedPlaybackId,
                description = currentPlayback?.request?.description,
            )
            Log.i(TAG, "Stopped cue playback playbackId=$stoppedPlaybackId reason=$reason")
        }
        currentPlayback = null
    }

    private fun releaseCurrentAudioTrackLocked(
        stopBeforeRelease: Boolean,
        releaseReason: String,
        playbackId: Int,
        description: String?,
    ) {
        currentAudioTrack?.let { audioTrack ->
            if (stopBeforeRelease) {
                runCatching {
                    audioTrack.stop()
                }.onFailure { error ->
                    Log.w(TAG, "Failed to stop cue playback", error)
                }
            }
            runCatching {
                audioTrack.release()
                Log.i(
                    TAG,
                    "Released cue playback playbackId=$playbackId " +
                        "cue=${description ?: "unknown"} stopBeforeRelease=$stopBeforeRelease " +
                        "reason=$releaseReason",
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to release cue playback", error)
            }
        }
        currentAudioTrack = null
    }

    private fun stopInternal(reason: String) {
        val pendingDescription = currentPlayback?.request?.description ?: pendingBeepPlayback?.description
        pendingBeepPlayback = null
        stopCurrentPlaybackLocked(reason = reason)
        Log.i(
            TAG,
            "Stopped cue processing cue=${pendingDescription ?: "none"} reason=$reason",
        )
    }

    private fun drainQueuedPlayback() {
        if (isReleased) {
            return
        }
        if (currentPlayback != null) {
            return
        }
        val nextBeep = pendingBeepPlayback ?: return
        pendingBeepPlayback = null
        startPlaybackLocked(nextBeep)
    }

    private fun PendingPlayback.playbackCleanupDelayMillis(): Long {
        val graceMillis = when (kind) {
            PlaybackKind.SPEECH -> SPEECH_PLAYBACK_CLEANUP_GRACE_MILLIS
            PlaybackKind.BEEP -> BEEP_PLAYBACK_CLEANUP_GRACE_MILLIS
        }
        return audioData.durationMillis + graceMillis
    }

    private companion object {
        private const val TAG = "PhaseAudioPlayer"
        private const val SPEECH_PLAYBACK_CLEANUP_GRACE_MILLIS = 200L
        private const val BEEP_PLAYBACK_CLEANUP_GRACE_MILLIS = 300L
        private const val PCM_FORMAT = 1
        private const val PCM_16_BIT = 16
    }

    private data class PendingPlayback(
        val audioData: WavAudioData,
        val description: String,
        val kind: PlaybackKind,
        val volume: Float,
        val beepPitchPreset: BeepPitchPreset?,
        val logEntryId: Long?,
        val phaseStart: WalkingPhase?,
        val onCompleted: (() -> Unit)?,
    )

    private data class ActivePlayback(
        val request: PendingPlayback,
        val playbackId: Int,
    )

    private enum class PlaybackKind {
        SPEECH,
        BEEP,
    }

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
            private const val DEFAULT_SAMPLE_RATE = 44_100
            private const val DEFAULT_BEEP_DURATION_MILLIS = 120
            private const val DEFAULT_BEEP_FREQUENCY_HZ = 880.0
            private const val BEEP_ENVELOPE_PORTION = 0.15

            fun generateBeep(
                sampleRate: Int = DEFAULT_SAMPLE_RATE,
                durationMillis: Int = DEFAULT_BEEP_DURATION_MILLIS,
                frequencyHz: Double = DEFAULT_BEEP_FREQUENCY_HZ,
            ): WavAudioData {
                val frameCount = ((sampleRate * durationMillis) / 1_000.0).roundToInt().coerceAtLeast(1)
                val pcmData = ByteArray(frameCount * Short.SIZE_BYTES)
                val attackFrames = (frameCount * BEEP_ENVELOPE_PORTION).roundToInt().coerceAtLeast(1)
                val releaseFrames = attackFrames
                for (frameIndex in 0 until frameCount) {
                    val timeSeconds = frameIndex.toDouble() / sampleRate.toDouble()
                    val envelope = when {
                        frameIndex < attackFrames ->
                            frameIndex.toDouble() / attackFrames.toDouble()
                        frameIndex >= frameCount - releaseFrames ->
                            (frameCount - frameIndex).toDouble() / releaseFrames.toDouble()
                        else -> 1.0
                    }.coerceIn(0.0, 1.0)
                    val sample = (sin(2.0 * PI * frequencyHz * timeSeconds) * Short.MAX_VALUE * envelope)
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                    val baseIndex = frameIndex * Short.SIZE_BYTES
                    pcmData[baseIndex] = (sample.toInt() and 0xFF).toByte()
                    pcmData[baseIndex + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                return WavAudioData(
                    sampleRate = sampleRate,
                    channelCount = 1,
                    pcmData = pcmData,
                )
            }

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
