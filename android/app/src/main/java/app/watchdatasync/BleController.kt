package app.watchdatasync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.UUID

data class FoundWatch(val device: BluetoothDevice, val name: String, val rssi: Int)

class BleController(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanActive = false
    private var gatt: BluetoothGatt? = null

    private val _devices = MutableStateFlow<List<FoundWatch>>(emptyList())
    val devices: StateFlow<List<FoundWatch>> = _devices.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()
    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val stopScanRunnable = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun startScan() {
        stopScan()
        _error.value = null
        val a = adapter
        if (a == null) { _status.value = "Bluetooth unavailable"; return }
        if (!a.isEnabled) { _status.value = "Turn on Bluetooth"; return }
        _devices.value = emptyList()
        scanner = a.bluetoothLeScanner
        _status.value = "Scanning for FT_38093…"
        try {
            scanner?.startScan(scanCallback)
            scanActive = true
            handler.postDelayed(stopScanRunnable, 12_000L)
        } catch (_: SecurityException) {
            _error.value = "Bluetooth scan permission is required"
            _status.value = "Permission required"
        } catch (e: Exception) {
            _error.value = "Scan failed: " + (e.message ?: e.javaClass.simpleName)
            _status.value = "Scan failed"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (scanActive) runCatching { scanner?.stopScan(scanCallback) }
        scanActive = false
        scanner = null
        if (!_connected.value && _status.value.startsWith("Scanning")) _status.value = "Ready"
    }

    fun setPermissionError() {
        _error.value = "Bluetooth permission is required"
        _status.value = "Permission required"
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        _error.value = null
        _heartRate.value = null
        _status.value = "Connecting…"
        closeGatt()
        try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            _error.value = "Bluetooth connect permission is required"
            _status.value = "Permission required"
        } catch (e: Exception) {
            _error.value = "Connection failed: " + (e.message ?: e.javaClass.simpleName)
            _status.value = "Connection failed"
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        closeGatt()
        _connected.value = false
        _heartRate.value = null
        _status.value = "Disconnected"
    }

    @SuppressLint("MissingPermission")
    fun syncTime() {
        val current = gatt ?: return
        if (!_connected.value) return
        val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID))
        val c = service?.getCharacteristic(UUID.fromString(FastrackProtocol.TIME_WRITE_UUID))
        if (service == null || c == null) { _error.value = "Time characteristic not found"; return }
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) { _error.value = "Time characteristic is not writable"; return }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = FastrackProtocol.buildTimeSyncPacket(Calendar.getInstance())
        _status.value = "Syncing watch time…"
        try {
            if (!current.writeCharacteristic(c)) {
                _error.value = "Time write was rejected"
                _status.value = "Connected • live heart rate active"
            }
        } catch (_: SecurityException) {
            _error.value = "Bluetooth connect permission is required"
            _status.value = "Permission required"
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureLiveHeartRate(current: BluetoothGatt): Boolean {
        val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID))
        val c = service?.getCharacteristic(UUID.fromString(FastrackProtocol.LIVE_DATA_UUID))
        if (service == null || c == null) { _error.value = "Live heart-rate characteristic not found"; return false }
        val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!notify && !indicate) { _error.value = "Live heart-rate characteristic is not notifiable"; return false }
        if (!current.setCharacteristicNotification(c, true)) { _error.value = "Could not enable live heart-rate notifications"; return false }
        val d = c.getDescriptor(UUID.fromString(CCCD_UUID)) ?: run { _error.value = "Live heart-rate CCCD not found"; return false }
        d.value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return try { current.writeDescriptor(d) } catch (_: SecurityException) { _error.value = "Bluetooth connect permission is required"; false }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            val byName = FastrackProtocol.isTargetName(name)
            val byService = result.scanRecord?.serviceUuids.orEmpty().any { FastrackProtocol.matchesService(it.uuid.toString()) }
            if (!byName && !byService) return
            val found = FoundWatch(result.device, name?.takeIf { it.isNotBlank() } ?: "FT_38093", result.rssi)
            _devices.value = (_devices.value.filterNot { it.device.address == found.device.address } + found).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) {
            scanActive = false
            _status.value = "Scan failed"
            _error.value = "BLE scan failed with code " + errorCode
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                _status.value = "Connected • discovering services…"
                if (!gatt.discoverServices()) _error.value = "Service discovery could not start"
                return
            }
            _connected.value = false
            _heartRate.value = null
            _status.value = if (newState == BluetoothProfile.STATE_DISCONNECTED) "Disconnected" else "Connection failed (" + status + ")"
            if (this@BleController.gatt === gatt) this@BleController.gatt = null
            runCatching { gatt.close() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { _error.value = "Service discovery failed (" + status + ")"; return }
            val service = gatt.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID))
            val time = service?.getCharacteristic(UUID.fromString(FastrackProtocol.TIME_WRITE_UUID))
            val live = service?.getCharacteristic(UUID.fromString(FastrackProtocol.LIVE_DATA_UUID))
            if (service == null || time == null || live == null) { _error.value = "FT_38093 service or characteristics not found"; _status.value = "Unsupported watch"; return }
            if (!configureLiveHeartRate(gatt)) { _status.value = "Connected • live heart-rate setup failed"; return }
            _status.value = "Connected • enabling live heart rate…"
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!descriptor.characteristic.uuid.toString().equals(FastrackProtocol.LIVE_DATA_UUID, ignoreCase = true)) return
            if (status == BluetoothGatt.GATT_SUCCESS) { _status.value = "Connected • live heart rate active"; syncTime() }
            else { _error.value = "Live heart-rate setup failed (" + status + ")"; _status.value = "Connected" }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { handleHeartRate(characteristic.value) }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleHeartRate(value) }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!characteristic.uuid.toString().equals(FastrackProtocol.TIME_WRITE_UUID, ignoreCase = true)) return
            if (status == BluetoothGatt.GATT_SUCCESS) { _status.value = "Connected • time synced • live heart rate active"; _error.value = null }
            else { _error.value = "Watch time sync failed (" + status + ")"; _status.value = "Connected • live heart rate active" }
        }
    }

    private fun handleHeartRate(value: ByteArray) {
        val bpm = FastrackProtocol.decodeLiveHeartRate(value) ?: return
        _heartRate.value = bpm
        _status.value = "Connected • live heart rate active"
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    companion object { private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb" }
}
