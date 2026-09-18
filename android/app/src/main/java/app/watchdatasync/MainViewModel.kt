package app.watchdatasync

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import app.watchdatasync.ble.BleGattClient
import app.watchdatasync.ble.BleScanner
import app.watchdatasync.model.WatchDevice
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = BleScanner(application)
    private val gattClient = BleGattClient(application)
    private var automaticDiscoveryEnabled = false
    private val preferences =
        application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _boundWatchAddress =
        MutableStateFlow(preferences.getString(KEY_BOUND_ADDRESS, null))
    private val _boundWatchName =
        MutableStateFlow(preferences.getString(KEY_BOUND_NAME, null))

    val boundWatchAddress: StateFlow<String?> = _boundWatchAddress
    val boundWatchName: StateFlow<String?> = _boundWatchName
    val liveHeartRate = gattClient.liveHeartRate

    val devices: StateFlow<List<WatchDevice>> = scanner.devices
    val connected = gattClient.connected
    val services = gattClient.services
    val values = gattClient.values
    val logs = gattClient.logs
    val error = gattClient.error
    val heartRateHistory = gattClient.heartRateHistory
    val spo2History = gattClient.spo2History
    val dailyActivity = gattClient.dailyActivity
    val batteryPercent = gattClient.batteryPercent
    val lastSyncAt = gattClient.lastSyncAt
    val syncing = gattClient.syncing

    init {
        scanner.onCompatibleDeviceFound = { device ->
            if (automaticDiscoveryEnabled && !connected.value) {
                automaticDiscoveryEnabled = false
                scanner.stop()
                connect(device.address)
            }
        }
    }

    fun startScan() = scanner.start(_boundWatchAddress.value)

    fun startAutomaticWatchDiscovery() {
        if (connected.value) return
        automaticDiscoveryEnabled = true

        val boundAddress = _boundWatchAddress.value
        if (boundAddress != null) {
            autoConnectBoundWatch()
            viewModelScope.launch {
                delay(2_500L)
                if (!connected.value && automaticDiscoveryEnabled) {
                    scanner.start(boundAddress, autoConnectFirst = true)
                }
            }
        } else {
            scanner.start(null, autoConnectFirst = true)
        }
    }

    fun stopScan() = scanner.stop()

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        try {
            val manager = getApplication<Application>()
                .getSystemService(BluetoothManager::class.java)
                ?: run {
                    gattClient.reportError("Bluetooth service is unavailable")
                    return
                }

            val adapter: BluetoothAdapter = manager.adapter
            if (!adapter.isEnabled) {
                gattClient.reportError("Bluetooth is turned off")
                return
            }

            val device = adapter.getRemoteDevice(address)
            val name = try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
            preferences.edit()
                .putString(KEY_BOUND_ADDRESS, address)
                .putString(KEY_BOUND_NAME, name ?: "FT_38093 watch")
                .apply()
            _boundWatchAddress.value = address
            _boundWatchName.value = name ?: "FT_38093 watch"

            scanner.stop()
            gattClient.connect(device)
        } catch (e: SecurityException) {
            gattClient.reportError("Bluetooth permission was denied")
        } catch (e: IllegalArgumentException) {
            gattClient.reportError("Invalid Bluetooth device address")
        } catch (e: Exception) {
            gattClient.reportError("Connection failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun readCharacteristic(serviceUuid: String, characteristicUuid: String): Boolean =
        gattClient.readCharacteristic(serviceUuid, characteristicUuid)

    fun enableNotifications(serviceUuid: String, characteristicUuid: String): Boolean =
        gattClient.enableNotifications(serviceUuid, characteristicUuid)

    fun refreshStandardData() = gattClient.syncNow()

    fun syncNow() = gattClient.syncNow()

    @SuppressLint("MissingPermission")
    fun autoConnectBoundWatch() {
        if (connected.value) return

        val address = _boundWatchAddress.value ?: return
        try {
            val manager = getApplication<Application>()
                .getSystemService(BluetoothManager::class.java)
                ?: return
            val adapter = manager.adapter
            if (!adapter.isEnabled) return

            val device = adapter.getRemoteDevice(address)
            gattClient.connect(device)
        } catch (e: SecurityException) {
            gattClient.reportError("Bluetooth permission is required to auto-connect the watch")
        } catch (e: IllegalArgumentException) {
            forgetBoundWatch()
        }
    }

    fun forgetBoundWatch() {
        preferences.edit()
            .remove(KEY_BOUND_ADDRESS)
            .remove(KEY_BOUND_NAME)
            .apply()
        _boundWatchAddress.value = null
        _boundWatchName.value = null
    }

    fun clearError() = gattClient.clearError()

    fun clearCapture() = gattClient.clearCapture()

    fun markCapture(label: String) = gattClient.markCapture(label)

    fun disconnect() {
        automaticDiscoveryEnabled = false
        scanner.stop()
        gattClient.disconnect()
    }

    private companion object {
        const val PREFS_NAME = "watch_preferences"
        const val KEY_BOUND_ADDRESS = "bound_watch_address"
        const val KEY_BOUND_NAME = "bound_watch_name"
    }

    override fun onCleared() {
        scanner.stop()
        gattClient.disconnect()
        super.onCleared()
    }
}
