package com.goenc.dailymotiontimer

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhaseTransitionLogEntry(
    val id: Long,
    val phase: WalkingPhase,
    val source: String,
    val theoreticalTransitionElapsedRealtime: Long,
    val detectedElapsedRealtime: Long,
    val enqueuedElapsedRealtime: Long,
    val playRequestedElapsedRealtime: Long? = null,
    val soundPoolPlayElapsedRealtime: Long? = null,
) {
    val detectedDelayMillis: Long
        get() = detectedElapsedRealtime - theoreticalTransitionElapsedRealtime

    val enqueuedDelayMillis: Long
        get() = enqueuedElapsedRealtime - theoreticalTransitionElapsedRealtime

    val playRequestedDelayMillis: Long?
        get() = playRequestedElapsedRealtime?.minus(theoreticalTransitionElapsedRealtime)

    val soundPoolPlayDelayMillis: Long?
        get() = soundPoolPlayElapsedRealtime?.minus(theoreticalTransitionElapsedRealtime)

    val displayDelayMillis: Long
        get() = soundPoolPlayDelayMillis
            ?: playRequestedDelayMillis
            ?: enqueuedDelayMillis
}

object PhaseTransitionLogStore {
    private const val TAG = "PhaseTransitionLog"
    private const val MAX_LOG_ENTRIES = 100

    private val lock = Any()
    private val entries = mutableListOf<PhaseTransitionLogEntry>()
    private val _entriesFlow = MutableStateFlow<List<PhaseTransitionLogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<PhaseTransitionLogEntry>> = _entriesFlow.asStateFlow()

    private var nextEntryId = 1L

    fun recordTransition(
        phase: WalkingPhase,
        source: String,
        theoreticalTransitionElapsedRealtime: Long,
        detectedElapsedRealtime: Long,
        enqueuedElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): Long {
        val entry = synchronized(lock) {
            val createdEntry =
                PhaseTransitionLogEntry(
                    id = nextEntryId++,
                    phase = phase,
                    source = source,
                    theoreticalTransitionElapsedRealtime = theoreticalTransitionElapsedRealtime,
                    detectedElapsedRealtime = detectedElapsedRealtime,
                    enqueuedElapsedRealtime = enqueuedElapsedRealtime,
                )
            entries.add(0, createdEntry)
            trimLocked()
            publishLocked()
            createdEntry
        }
        Log.i(
            TAG,
            "phase=${entry.phase.name} source=${entry.source} " +
                "theoretical=${entry.theoreticalTransitionElapsedRealtime} " +
                "detected=${entry.detectedElapsedRealtime} " +
                "enqueued=${entry.enqueuedElapsedRealtime} " +
                "detectedDelay=${entry.detectedDelayMillis} " +
                "enqueuedDelay=${entry.enqueuedDelayMillis}",
        )
        return entry.id
    }

    fun markPlayRequested(
        entryId: Long,
        playRequestedElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ) {
        val entry =
            updateEntry(entryId) { currentEntry ->
                currentEntry.copy(playRequestedElapsedRealtime = playRequestedElapsedRealtime)
            } ?: return
        Log.i(
            TAG,
            "phase=${entry.phase.name} source=${entry.source} " +
                "playRequest=${entry.playRequestedElapsedRealtime} " +
                "playRequestDelay=${entry.playRequestedDelayMillis}",
        )
    }

    fun markSoundPoolPlay(
        entryId: Long,
        soundPoolPlayElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ) {
        val entry =
            updateEntry(entryId) { currentEntry ->
                currentEntry.copy(soundPoolPlayElapsedRealtime = soundPoolPlayElapsedRealtime)
            } ?: return
        Log.i(
            TAG,
            "phase=${entry.phase.name} source=${entry.source} " +
                "soundPoolPlay=${entry.soundPoolPlayElapsedRealtime} " +
                "soundPoolPlayDelay=${entry.soundPoolPlayDelayMillis}",
        )
    }

    private fun updateEntry(
        entryId: Long,
        transform: (PhaseTransitionLogEntry) -> PhaseTransitionLogEntry,
    ): PhaseTransitionLogEntry? {
        return synchronized(lock) {
            val index = entries.indexOfFirst { entry -> entry.id == entryId }
            if (index < 0) {
                null
            } else {
                val updatedEntry = transform(entries[index])
                entries[index] = updatedEntry
                publishLocked()
                updatedEntry
            }
        }
    }

    private fun trimLocked() {
        while (entries.size > MAX_LOG_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
    }

    private fun publishLocked() {
        _entriesFlow.value = entries.toList()
    }
}
