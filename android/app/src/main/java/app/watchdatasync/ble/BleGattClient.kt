package app.watchdatasync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.watchdatasync.model.GattCharacteristic
import app.watchdatasync.model.GattService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BleGattClient(private val context: Context) {
    private var gatt: BluetoothGatt? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _services = MutableStateFlow<List<GattService>>(emptyList())
    val services: StateFlow<List<GattService>> = _services.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        try {
            _error.value = null
            appendLog("CONNECT " + device.address + " " + device.name.orEmpty())
            gatt = device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
            if (gatt == null) {
                reportError("Android could not create a BLE connection")
            }
        } catch (e: SecurityException) {
            reportError("Bluetooth permission was denied")
        } catch (e: Exception) {
            reportError("BLE connection failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connected.value = false
        _services.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun requestMtu(mtu: Int = 247) {
        gatt?.requestMtu(mtu)
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: String, characteristicUuid: String): Boolean {
        val service = gatt?.getService(UUID.fromString(serviceUuid)) ?: return false
        val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid)) ?: return false

        val enabled = gatt?.setCharacteristicNotification(characteristic, true) ?: false
        val cccd = characteristic.descriptors.firstOrNull {
            it.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        }

        if (cccd != null) {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(cccd)
        }

        appendLog("NOTIFY " + characteristicUuid + " enabled=" + enabled)
        return enabled
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            val stateText = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> newState.toString()
            }
            appendLog("STATE status=" + status + " state=" + stateText)
            _connected.value = newState == BluetoothProfile.STATE_CONNECTED

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    @Suppress("DEPRECATION")
                    val started = gatt.discoverServices()
                    appendLog("DISCOVER started=" + started)
                    if (!started) reportError("GATT service discovery could not start")
                } catch (e: SecurityException) {
                    reportError("Bluetooth permission was denied")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && status != BluetoothGatt.GATT_SUCCESS) {
                val reason = when (status) {
                    GATT_CONN_TERMINATE_PEER_USER ->
                        "The watch/peripheral terminated the BLE connection"
                    GATT_CONN_TERMINATE_LOCAL_HOST ->
                        "Android terminated the BLE connection"
                    GATT_CONN_TIMEOUT ->
                        "BLE connection timed out"
                    else ->
                        "BLE disconnected unexpectedly"
                }

                reportError(reason + " (status " + status + ")")
                runCatching { gatt.close() }
                if (this@BleGattClient.gatt == gatt) this@BleGattClient.gatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            appendLog("SERVICES status=" + status)
            if (status != BluetoothGatt.GATT_SUCCESS) return

            _services.value = gatt.services.map { service ->
                GattService(
                    uuid = service.uuid.toString(),
                    characteristics = service.characteristics.map { characteristic ->
                        GattCharacteristic(
                            uuid = characteristic.uuid.toString(),
                            properties = propertyNames(characteristic.properties),
                        )
                    },
                )
            }

            gatt.services.forEach { service ->
                appendLog("SERVICE " + service.uuid)
                service.characteristics.forEach { characteristic ->
                    appendLog(
                        "CHAR " + characteristic.uuid +
                            " props=" + propertyNames(characteristic.properties).joinToString(","),
                    )
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()
            appendLog("NOTIFICATION " + characteristic.uuid + " " + hex(value))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            appendLog("NOTIFICATION " + characteristic.uuid + " " + hex(value))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            appendLog("MTU mtu=" + mtu + " status=" + status)
        }
    }

    private companion object {
        // Android Bluetooth stack / HCI disconnect reason 0x13.
        // This means the peer side terminated the connection.
        const val GATT_CONN_TERMINATE_PEER_USER = 19

        // Android Bluetooth stack / HCI disconnect reason for local host termination.
        const val GATT_CONN_TERMINATE_LOCAL_HOST = 22

        // HCI connection timeout.
        const val GATT_CONN_TIMEOUT = 8
    }

    private fun propertyNames(properties: Int): List<String> = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun reportError(message: String) {
        _error.value = message
        appendLog("ERROR " + message)
    }

    fun clearError() {
        _error.value = null
    }

    private fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(300)
    }
}
