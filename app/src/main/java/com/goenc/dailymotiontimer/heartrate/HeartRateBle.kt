package com.goenc.dailymotiontimer.heartrate

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID

internal object HeartRateBleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}

object HeartRateParser {
    fun parse(value: ByteArray): Int? {
        if (value.size < 2) return null
        val usesUInt16 = value[0].toInt() and 0x01 != 0
        return if (usesUInt16) {
            if (value.size < 3) null else {
                (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            }
        } else {
            value[1].toInt() and 0xFF
        }
    }
}

internal object BatteryLevelParser {
    fun parse(value: ByteArray): Int? = value.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it <= 100 }
}

internal class HeartRateScanner(
    private val adapter: BluetoothAdapter,
    private val onDevicesChanged: (List<HeartRateDevice>) -> Unit,
    private val handleScanFailed: (Int) -> Unit,
) {
    private val devices = linkedMapOf<String, HeartRateDevice>()
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = update(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::update)
        override fun onScanFailed(errorCode: Int) = handleScanFailed(errorCode)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        devices.clear()
        adapter.bluetoothLeScanner?.startScan(callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        adapter.bluetoothLeScanner?.stopScan(callback)
    }

    @SuppressLint("MissingPermission")
    private fun update(result: ScanResult) {
        val supportsHeartRate = result.scanRecord?.serviceUuids
            ?.any { it.uuid == HeartRateBleConstants.SERVICE_UUID } == true
        val device = HeartRateDevice(
            name = result.device.name ?: result.scanRecord?.deviceName ?: "名前なし",
            address = result.device.address,
            rssi = result.rssi,
            supportsHeartRate = supportsHeartRate,
        )
        devices[device.address] = device
        onDevicesChanged(
            devices.values.sortedWith(
                compareByDescending<HeartRateDevice> { it.supportsHeartRate }.thenByDescending { it.rssi },
            ),
        )
    }
}

internal class HeartRateBleClient(
    private val context: Context,
    private val onStateChanged: (HeartRateConnectionState, String?) -> Unit,
    private val onHeartRateChanged: (Int) -> Unit,
    private val onBatteryLevelChanged: (Int) -> Unit,
) {
    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStateChanged(HeartRateConnectionState.ERROR, "接続エラー: $status")
                close(gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStateChanged(HeartRateConnectionState.CONNECTED, null)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStateChanged(HeartRateConnectionState.DISCONNECTED, null)
                    close(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("サービス検出エラー: $status")
            val characteristic = gatt.getService(HeartRateBleConstants.SERVICE_UUID)
                ?.getCharacteristic(HeartRateBleConstants.MEASUREMENT_UUID)
                ?: return fail("心拍計測サービスが見つかりません")
            enableNotifications(gatt, characteristic)
        }

        @Deprecated("Android 13未満で使用")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            HeartRateParser.parse(characteristic.value)?.let(onHeartRateChanged)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            HeartRateParser.parse(value)?.let(onHeartRateChanged)
        }

        @Deprecated("Android 13未満で使用")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == HeartRateBleConstants.BATTERY_LEVEL_UUID) {
                BatteryLevelParser.parse(characteristic.value)?.let(onBatteryLevelChanged)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == HeartRateBleConstants.BATTERY_LEVEL_UUID) {
                BatteryLevelParser.parse(value)?.let(onBatteryLevelChanged)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("心拍通知の設定エラー: $status")
                return
            }
            readBatteryLevel(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return fail("心拍通知を有効化できません")
        }
        val descriptor = characteristic.getDescriptor(HeartRateBleConstants.CLIENT_CONFIG_UUID)
            ?: return fail("心拍通知の設定情報が見つかりません")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(gatt: BluetoothGatt) {
        val characteristic = gatt.getService(HeartRateBleConstants.BATTERY_SERVICE_UUID)
            ?.getCharacteristic(HeartRateBleConstants.BATTERY_LEVEL_UUID)
            ?: return
        gatt.readCharacteristic(characteristic)
    }

    private fun fail(message: String) {
        onStateChanged(HeartRateConnectionState.ERROR, message)
        disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun close(closedGatt: BluetoothGatt) {
        closedGatt.close()
        if (gatt === closedGatt) gatt = null
    }
}
