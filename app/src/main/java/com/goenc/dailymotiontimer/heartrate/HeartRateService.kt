package com.goenc.dailymotiontimer.heartrate

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goenc.dailymotiontimer.MainActivity
import com.goenc.dailymotiontimer.R
import com.goenc.dailymotiontimer.TimerUiState
import com.goenc.dailymotiontimer.WalkingTimerStateStore
import java.util.Locale

class HeartRateService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: HeartRatePreferences
    private lateinit var alertEngine: HeartRateAlertEngine
    private var client: HeartRateBleClient? = null
    private var textToSpeech: TextToSpeech? = null
    private var speechReady = false
    private var pendingSpeech: String? = null
    private var reconnectEnabled = false
    private var state = HeartRateConnectionState.DISCONNECTED
    private var heartRate = 0
    private var averageHeartRate: Int? = null
    private var zone: HeartRateZone? = null
    private var rule = HeartRateZoneCalculator.buildRule(HeartRateSettings())
    private val reconnectRunnable = Runnable(::connectSavedDevice)

    override fun onCreate() {
        super.onCreate()
        preferences = HeartRatePreferences(this)
        val settings = preferences.loadSettings()
        rule = HeartRateZoneCalculator.buildRule(settings)
        textToSpeech = TextToSpeech(applicationContext, this)
        alertEngine = HeartRateAlertEngine(
            initialSettings = settings,
            onSnapshot = { average, currentZone, currentRule ->
                averageHeartRate = average
                zone = currentZone
                rule = currentRule
                HeartRateController.publishMeasurement(heartRate, average, currentZone, currentRule)
            },
            onAlert = ::speak,
        )
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnectAndStop(clearDevice = false)
            ACTION_FORGET_DEVICE -> disconnectAndStop(clearDevice = true)
            ACTION_UPDATE_SETTINGS -> alertEngine.updateSettings(preferences.loadSettings())
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("接続を準備中"))
                reconnectEnabled = true
                if (state != HeartRateConnectionState.CONNECTED && state != HeartRateConnectionState.CONNECTING) {
                    connectSavedDevice()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectSavedDevice() {
        handler.removeCallbacks(reconnectRunnable)
        if (!hasConnectPermission()) {
            updateState(HeartRateConnectionState.ERROR, "Bluetooth権限が必要です")
            return
        }
        val savedDevice = preferences.loadDevice() ?: return disconnectAndStop(clearDevice = false)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateState(HeartRateConnectionState.ERROR, "Bluetoothを有効にしてください")
            scheduleReconnect()
            return
        }
        updateState(HeartRateConnectionState.CONNECTING)
        val device = runCatching { adapter.getRemoteDevice(savedDevice.address) }.getOrElse {
            updateState(HeartRateConnectionState.ERROR, "保存したデバイス情報が不正です")
            return
        }
        client?.disconnect()
        client = HeartRateBleClient(
            context = this,
            onStateChanged = ::handleClientState,
            onHeartRateChanged = { value ->
                heartRate = value
                alertEngine.onHeartRateSample(value, SystemClock.elapsedRealtime())
                updateNotification(savedDevice)
            },
        ).also { it.connect(device) }
    }

    private fun handleClientState(newState: HeartRateConnectionState, message: String?) {
        handler.post {
            updateState(newState, message)
            if (newState == HeartRateConnectionState.DISCONNECTED || newState == HeartRateConnectionState.ERROR) {
                client = null
                scheduleReconnect()
            }
        }
    }

    private fun updateState(newState: HeartRateConnectionState, message: String? = null) {
        state = newState
        if (newState != HeartRateConnectionState.CONNECTED) {
            heartRate = 0
            averageHeartRate = null
            zone = null
            alertEngine.reset()
        }
        HeartRateController.publishConnectionState(newState, message)
        preferences.loadDevice()?.let(::updateNotification)
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled || preferences.loadDevice() == null) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun disconnectAndStop(clearDevice: Boolean) {
        reconnectEnabled = false
        handler.removeCallbacks(reconnectRunnable)
        client?.disconnect()
        client = null
        if (clearDevice) preferences.clearDevice()
        updateState(HeartRateConnectionState.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(device: HeartRateDevice) {
        if (!canPostNotifications()) return
        val content = when {
            state == HeartRateConnectionState.CONNECTED && heartRate > 0 -> "${device.name}: $heartRate bpm"
            else -> "${device.name}: ${state.label}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("心拍モニター")
        .setContentText(content)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "心拍センサー接続", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = textToSpeech?.setLanguage(Locale.JAPAN) ?: return
        speechReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (speechReady) pendingSpeech?.let(::speak)
        pendingSpeech = null
    }

    private fun speak(message: String) {
        if (!shouldAnnounceForCurrentTimerState()) {
            pendingSpeech = null
            textToSpeech?.stop()
            return
        }
        if (!speechReady) {
            pendingSpeech = message
            return
        }
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "heart_rate_alert")
    }

    private fun shouldAnnounceForCurrentTimerState(): Boolean {
        val timerState = currentTimerState() ?: return false
        if (!timerState.isRunning) {
            return false
        }
        return preferences.loadSettings().alertPhaseMode.shouldAnnounce(timerState.currentPhase)
    }

    private fun currentTimerState(): TimerUiState? {
        val persistedState = WalkingTimerStateStore.load(this) ?: return null
        return persistedState.toUiState(SystemClock.elapsedRealtime())
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        reconnectEnabled = false
        handler.removeCallbacks(reconnectRunnable)
        client?.disconnect()
        alertEngine.reset()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.goenc.dailymotiontimer.heartrate.CONNECT"
        const val ACTION_DISCONNECT = "com.goenc.dailymotiontimer.heartrate.DISCONNECT"
        const val ACTION_FORGET_DEVICE = "com.goenc.dailymotiontimer.heartrate.FORGET_DEVICE"
        const val ACTION_UPDATE_SETTINGS = "com.goenc.dailymotiontimer.heartrate.UPDATE_SETTINGS"
        private const val CHANNEL_ID = "heart_rate_connection"
        private const val NOTIFICATION_ID = 1002
        private const val RECONNECT_DELAY_MS = 5_000L

        fun createIntent(context: Context, action: String): Intent =
            Intent(context.applicationContext, HeartRateService::class.java).setAction(action)
    }
}
