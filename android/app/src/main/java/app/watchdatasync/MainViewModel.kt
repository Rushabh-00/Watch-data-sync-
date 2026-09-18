package app.watchdatasync

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import app.watchdatasync.ble.BleGattClient
import app.watchdatasync.ble.BleScanner
import app.watchdatasync.model.WatchDevice
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = BleScanner(application)
    private val gattClient = BleGattClient(application)

    val devices: StateFlow<List<WatchDevice>> = scanner.devices
    val connected = gattClient.connected
    val services = gattClient.services
    val values = gattClient.values
    val logs = gattClient.logs
    val error = gattClient.error

    fun startScan() = scanner.start()

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

    fun refreshStandardData() = gattClient.refreshStandardData()

    fun clearError() = gattClient.clearError()

    fun disconnect() = gattClient.disconnect()

    override fun onCleared() {
        scanner.stop()
        gattClient.disconnect()
        super.onCleared()
    }
}
