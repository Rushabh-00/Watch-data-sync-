package app.watchdatasync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import app.watchdatasync.model.WatchDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleScanner(context: Context) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<List<WatchDevice>>(emptyList())
    val devices: StateFlow<List<WatchDevice>> = _devices.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start() {
        val bleScanner = scanner ?: return
        _devices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(null, settings, callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        scanner?.stopScan(callback)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName
                ?: device.name?.takeIf { it.isNotBlank() }
                ?: "Unnamed BLE device"

            val next = WatchDevice(
                name = name,
                address = device.address,
                rssi = result.rssi,
                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            )

            _devices.value = buildList {
                add(next)
                addAll(_devices.value.filterNot { it.address == next.address })
            }.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _devices.value = listOf(
                WatchDevice(
                    name = "Scan failed: $errorCode",
                    address = "scanner-error",
                    rssi = 0,
                    bonded = false,
                ),
            )
        }
    }
}
