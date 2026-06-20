package com.goenc.dailymotiontimer.heartrate

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HeartRateController {
    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState.asStateFlow()
    private var scanner: HeartRateScanner? = null
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val preferences = HeartRatePreferences(context.applicationContext)
        val settings = preferences.loadSettings()
        _uiState.value = _uiState.value.copy(
            savedDevice = preferences.loadDevice(),
            settings = settings,
            rule = HeartRateZoneCalculator.buildRule(settings),
        )
        initialized = true
    }

    fun connectSavedDevice(context: Context) {
        initialize(context)
        if (_uiState.value.savedDevice == null || !hasBluetoothPermissions(context)) return
        ContextCompat.startForegroundService(context, HeartRateService.createIntent(context, HeartRateService.ACTION_CONNECT))
    }

    fun startScan(context: Context) {
        initialize(context)
        if (!hasBluetoothPermissions(context)) {
            reportPermissionDenied()
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _uiState.value = _uiState.value.copy(
                connectionState = HeartRateConnectionState.ERROR,
                errorMessage = "Bluetoothを有効にしてください",
            )
            return
        }
        stopScan()
        _uiState.value = _uiState.value.copy(
            connectionState = HeartRateConnectionState.SCANNING,
            devices = emptyList(),
            errorMessage = null,
        )
        scanner = HeartRateScanner(
            adapter = adapter,
            onDevicesChanged = { devices ->
                _uiState.value = _uiState.value.copy(devices = devices)
            },
            handleScanFailed = { code ->
                _uiState.value = _uiState.value.copy(
                    connectionState = HeartRateConnectionState.ERROR,
                    errorMessage = "検索エラー: $code",
                )
            },
        ).also { it.start() }
    }

    fun stopScan() {
        scanner?.stop()
        scanner = null
    }

    fun selectDevice(context: Context, address: String) {
        val selected = _uiState.value.devices.firstOrNull { it.address == address } ?: return
        stopScan()
        HeartRatePreferences(context).saveDevice(selected)
        _uiState.value = _uiState.value.copy(
            savedDevice = selected,
            devices = emptyList(),
            connectionState = HeartRateConnectionState.CONNECTING,
            errorMessage = null,
        )
        ContextCompat.startForegroundService(context, HeartRateService.createIntent(context, HeartRateService.ACTION_CONNECT))
    }

    fun disconnect(context: Context) {
        context.startService(HeartRateService.createIntent(context, HeartRateService.ACTION_DISCONNECT))
    }

    fun forgetDevice(context: Context) {
        HeartRatePreferences(context).clearDevice()
        _uiState.value = HeartRateUiState(settings = _uiState.value.settings).copy(
            rule = HeartRateZoneCalculator.buildRule(_uiState.value.settings),
        )
        context.startService(HeartRateService.createIntent(context, HeartRateService.ACTION_FORGET_DEVICE))
    }

    fun updateSettings(
        context: Context,
        targetLowerBpm: Int,
        targetUpperBpm: Int,
        dangerThresholdBpm: Int,
        alertsEnabled: Boolean,
        alertPhaseMode: HeartRateAlertPhaseMode,
    ) {
        val normalizedTargetLower = targetLowerBpm.coerceIn(
            MIN_HEART_RATE_THRESHOLD_BPM,
            MAX_HEART_RATE_THRESHOLD_BPM - 2,
        )
        val normalizedTargetUpper = targetUpperBpm.coerceIn(
            normalizedTargetLower + 1,
            MAX_HEART_RATE_THRESHOLD_BPM - 1,
        )
        val normalizedDangerThreshold = dangerThresholdBpm.coerceIn(
            normalizedTargetUpper + 1,
            MAX_HEART_RATE_THRESHOLD_BPM,
        )
        val settings = _uiState.value.settings.copy(
            targetLowerBpm = normalizedTargetLower,
            targetUpperBpm = normalizedTargetUpper,
            dangerThresholdBpm = normalizedDangerThreshold,
            alertsEnabled = alertsEnabled,
            alertPhaseMode = alertPhaseMode,
        )
        HeartRatePreferences(context).saveSettings(settings)
        _uiState.value = _uiState.value.copy(
            settings = settings,
            rule = HeartRateZoneCalculator.buildRule(settings),
            zone = null,
            averageHeartRate = null,
        )
        if (
            _uiState.value.connectionState == HeartRateConnectionState.CONNECTED ||
            _uiState.value.connectionState == HeartRateConnectionState.CONNECTING
        ) {
            context.startService(HeartRateService.createIntent(context, HeartRateService.ACTION_UPDATE_SETTINGS))
        }
    }

    fun reportPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            connectionState = HeartRateConnectionState.ERROR,
            errorMessage = "Bluetooth権限が必要です",
        )
    }

    internal fun publishConnectionState(state: HeartRateConnectionState, message: String? = null) {
        _uiState.value = _uiState.value.copy(
            connectionState = state,
            heartRate = if (state == HeartRateConnectionState.CONNECTED) _uiState.value.heartRate else 0,
            averageHeartRate = if (state == HeartRateConnectionState.CONNECTED) _uiState.value.averageHeartRate else null,
            zone = if (state == HeartRateConnectionState.CONNECTED) _uiState.value.zone else null,
            errorMessage = message,
        )
    }

    internal fun publishMeasurement(heartRate: Int, averageHeartRate: Int?, zone: HeartRateZone?, rule: HeartRateRule) {
        _uiState.value = _uiState.value.copy(
            heartRate = heartRate,
            averageHeartRate = averageHeartRate,
            zone = zone,
            rule = rule,
        )
    }

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasBluetoothPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
