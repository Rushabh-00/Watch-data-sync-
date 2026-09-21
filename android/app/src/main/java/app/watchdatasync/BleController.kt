package app.watchdatasync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class FoundWatch(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
)

class BleController(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    private val foundByAddress = LinkedHashMap<String, FoundWatch>()

    private val _devices = MutableStateFlow<List<FoundWatch>>(emptyList())
    val devices: StateFlow<List<FoundWatch>> = _devices.asStateFlow()

    private val stopRunnable = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun startScan() {
        stopScan()
        val a = adapter ?: return
        if (!a.isEnabled) return

        foundByAddress.clear()
        _devices.value = emptyList()
        scanner = a.bluetoothLeScanner

        try {
            scanner?.startScan(scanCallback)
            scanning = true
            handler.postDelayed(stopRunnable, 12_000L)
        } catch (_: SecurityException) {
            // Activity handles the permission error through the permission flow.
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        if (scanning) runCatching { scanner?.stopScan(scanCallback) }
        scanning = false
        scanner = null
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull()

            val byName = FastrackProtocol.isTargetName(name)
            val byService = result.scanRecord?.serviceUuids.orEmpty().any {
                FastrackProtocol.matchesService(it.uuid.toString())
            }

            if (!byName && !byService) return

            val found = FoundWatch(
                device = result.device,
                name = name?.takeIf { it.isNotBlank() } ?: "FT_38093",
                rssi = result.rssi,
            )

            val previous = foundByAddress[found.device.address]
            if (
                previous != null &&
                previous.name == found.name &&
                abs(previous.rssi - found.rssi) < 2
            ) {
                return
            }

            foundByAddress[found.device.address] = found
            _devices.value = foundByAddress.values
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }
}
