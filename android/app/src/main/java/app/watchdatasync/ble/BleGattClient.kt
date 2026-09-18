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
import app.watchdatasync.model.GattValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BleGattClient(private val context: Context) {
    private var gatt: BluetoothGatt? = null
    private var disconnectRequested = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _services = MutableStateFlow<List<GattService>>(emptyList())
    val services: StateFlow<List<GattService>> = _services.asStateFlow()

    private val _values = MutableStateFlow<List<GattValue>>(emptyList())
    val values: StateFlow<List<GattValue>> = _values.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        disconnectRequested = false
        _error.value = null
        _services.value = emptyList()
        _values.value = emptyList()

        try {
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
        disconnectRequested = true
        val current = gatt
        gatt = null
        current?.disconnect()
        current?.close()
        _connected.value = false
        _services.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun requestMtu(mtu: Int = 247) {
        gatt?.requestMtu(mtu)
    }

    @SuppressLint("MissingPermission")
    fun readCharacteristic(serviceUuid: String, characteristicUuid: String): Boolean {
        val currentGatt = gatt ?: return false
        return try {
            val service = currentGatt.getService(UUID.fromString(serviceUuid))
            val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUuid))
            if (service == null || characteristic == null) {
                appendLog("READ missing " + serviceUuid + "/" + characteristicUuid)
                return false
            }

            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                appendLog("READ unsupported " + characteristicUuid)
                return false
            }

            val started = currentGatt.readCharacteristic(characteristic)
            appendLog("READ " + characteristicUuid + " started=" + started)
            started
        } catch (e: SecurityException) {
            reportError("Bluetooth permission was denied")
            false
        } catch (e: IllegalArgumentException) {
            appendLog("READ invalid UUID " + characteristicUuid)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: String, characteristicUuid: String): Boolean {
        val currentGatt = gatt ?: return false
        return try {
            val service = currentGatt.getService(UUID.fromString(serviceUuid))
            val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUuid))

            if (service == null || characteristic == null) {
                appendLog("NOTIFY missing " + serviceUuid + "/" + characteristicUuid)
                return false
            }

            val supportsNotify =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val supportsIndicate =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

            if (!supportsNotify && !supportsIndicate) {
                appendLog("NOTIFY unsupported " + characteristicUuid)
                return false
            }

            val localEnabled = currentGatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.descriptors.firstOrNull {
                it.uuid == UUID.fromString(CCCD_UUID)
            }

            if (cccd == null) {
                appendLog("NOTIFY no CCCD " + characteristicUuid + " local=" + localEnabled)
                return localEnabled
            }

            @Suppress("DEPRECATION")
            cccd.value = if (supportsNotify) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            @Suppress("DEPRECATION")
            val writeStarted = currentGatt.writeDescriptor(cccd)
            appendLog(
                "NOTIFY " + characteristicUuid +
                    " local=" + localEnabled +
                    " descriptorWrite=" + writeStarted,
            )
            localEnabled && writeStarted
        } catch (e: SecurityException) {
            reportError("Bluetooth permission was denied")
            false
        } catch (e: IllegalArgumentException) {
            appendLog("NOTIFY invalid UUID " + characteristicUuid)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshStandardData() {
        val currentGatt = gatt ?: return
        val battery = currentGatt.services
            .asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull { it.uuid.toString().equals(BATTERY_LEVEL_UUID, ignoreCase = true) }

        if (battery != null &&
            battery.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            val serviceUuid = currentGatt.services.firstOrNull { service ->
                service.characteristics.any { it.uuid == battery.uuid }
            }?.uuid?.toString()

            if (serviceUuid != null) {
                readCharacteristic(serviceUuid, battery.uuid.toString())
            }
        } else {
            appendLog("STANDARD battery characteristic not found/readable")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (this@BleGattClient.gatt != gatt) {
                runCatching { gatt.close() }
                appendLog("STATE stale callback ignored status=" + status + " state=" + newState)
                return
            }

            val stateText = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> newState.toString()
            }
            appendLog("STATE status=" + status + " state=" + stateText)

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                try {
                    @Suppress("DEPRECATION")
                    val started = gatt.discoverServices()
                    appendLog("DISCOVER started=" + started)
                    if (!started) reportError("GATT service discovery could not start")
                } catch (e: SecurityException) {
                    reportError("Bluetooth permission was denied")
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val userRequested = disconnectRequested
                disconnectRequested = false
                _connected.value = false
                _services.value = emptyList()
                runCatching { gatt.close() }
                if (this@BleGattClient.gatt == gatt) {
                    this@BleGattClient.gatt = null
                }

                if (!userRequested && status != BluetoothGatt.GATT_SUCCESS) {
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
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@BleGattClient.gatt != gatt) return

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

            refreshStandardData()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("READ_RESULT " + characteristic.uuid + " status=" + status)
                return
            }

            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()
            recordValue(gatt, characteristic, value, "READ")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("READ_RESULT " + characteristic.uuid + " status=" + status)
                return
            }

            recordValue(gatt, characteristic, value, "READ")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()
            recordValue(gatt, characteristic, value, "NOTIFICATION")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            recordValue(gatt, characteristic, value, "NOTIFICATION")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            appendLog("CCCD " + descriptor.characteristic.uuid + " status=" + status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            appendLog("MTU mtu=" + mtu + " status=" + status)
        }
    }

    private fun recordValue(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        source: String,
    ) {
        if (this.gatt != gatt) return

        val serviceUuid = gatt.services.firstOrNull { service ->
            service.characteristics.any { it.uuid == characteristic.uuid }
        }?.uuid?.toString() ?: "unknown"

        val item = GattValue(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristic.uuid.toString(),
            timestamp = timestamp(),
            hex = hex(value),
            ascii = ascii(value),
            decoded = decodeStandardValue(characteristic.uuid, value),
        )

        val existing = _values.value.filterNot { it.key == item.key }
        _values.value = (existing + item).takeLast(80)
        appendLog(
            source + " " + characteristic.uuid +
                " value=" + item.hex +
                (item.decoded?.let { " decoded=" + it } ?: ""),
        )
    }

    private fun decodeStandardValue(uuid: UUID, value: ByteArray): String? {
        val id = uuid.toString().lowercase(Locale.ROOT)
        if (id == BATTERY_LEVEL_UUID) {
            return value.firstOrNull()?.let { "Battery " + (it.toInt() and 0xFF) + "%" }
        }

        if (id == HEART_RATE_MEASUREMENT_UUID && value.isNotEmpty()) {
            val flags = value[0].toInt() and 0xFF
            var index = 1
            if (index >= value.size) return null

            val heartRate = if (flags and 0x01 == 0) {
                value[index].toInt() and 0xFF
            } else {
                if (index + 1 >= value.size) return null
                (value[index].toInt() and 0xFF) or
                    ((value[index + 1].toInt() and 0xFF) shl 8)
            }
            return "Heart rate " + heartRate + " bpm"
        }

        return if (isStandardTextCharacteristic(id)) {
            ascii(value).takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun isStandardTextCharacteristic(uuid: String): Boolean = uuid in setOf(
        "00002a24-0000-1000-8000-00805f9b34fb",
        "00002a25-0000-1000-8000-00805f9b34fb",
        "00002a26-0000-1000-8000-00805f9b34fb",
        "00002a27-0000-1000-8000-00805f9b34fb",
        "00002a28-0000-1000-8000-00805f9b34fb",
        "00002a29-0000-1000-8000-00805f9b34fb",
    )

    private fun propertyNames(properties: Int): List<String> = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun ascii(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8)
            .map { if (it.code in 32..126) it else '·' }
            .joinToString("")

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

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

    private companion object {
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

        const val GATT_CONN_TERMINATE_PEER_USER = 19
        const val GATT_CONN_TERMINATE_LOCAL_HOST = 22
        const val GATT_CONN_TIMEOUT = 8
    }
}
