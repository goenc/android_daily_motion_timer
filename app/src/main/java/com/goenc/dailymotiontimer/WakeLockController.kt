package com.goenc.dailymotiontimer

import android.content.Context
import android.os.PowerManager
import android.util.Log

internal class WakeLockController(
    private val context: Context,
    private val tag: String,
) {
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire() {
        val lock = wakeLock ?: create()?.also { wakeLock = it } ?: return
        if (lock.isHeld) return

        runCatching {
            lock.acquire()
            Log.i(tag, "WakeLock acquired")
        }.onFailure { error ->
            Log.e(tag, "Failed to acquire WakeLock", error)
        }
    }

    fun release() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) return

        runCatching {
            lock.release()
            Log.i(tag, "WakeLock released")
        }.onFailure { error ->
            Log.e(tag, "Failed to release WakeLock", error)
        }
    }

    private fun create(): PowerManager.WakeLock? {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: run {
            Log.w(tag, "PowerManager was unavailable, WakeLock was not created")
            return null
        }
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:WalkingTimerService",
        ).apply {
            setReferenceCounted(false)
        }
    }
}
