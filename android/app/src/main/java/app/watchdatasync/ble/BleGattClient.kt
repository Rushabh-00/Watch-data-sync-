package app.watchdatasync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.watchdatasync.model.GattCharacteristic
import app.watchdatasync.model.GattService
import app.watchdatasync.model.GattValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

class BleGattClient(private val context: Context) {
    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var disconnectRequested = false
    private var reconnectAttempt = 0

    private val handler = Handler(Looper.getMainLooper())
    private var operationTimeout: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    private sealed interface GattOperation {
        data class Read(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
        ) : GattOperation

        data class EnableNotification(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
        ) : GattOperation
    }

    private val operationQueue = ArrayDeque<GattOperation>()
    private val queuedOperationKeys = mutableSetOf<String>()
    private var activeOperation: GattOperation? = null

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
        cancelReconnect()
        reconnectAttempt = 0
        closeGatt(userRequested = true)

        disconnectRequested = false
        lastDevice = device
        _error.value = null
        _services.value = emptyList()
        _values.value = emptyList()
        clearOperationQueue()

        openGatt(device, reconnect = false)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, reconnect: Boolean) {
        try {
            appendLog(
                "CONNECT " + device.address +
                    " " + device.name.orEmpty() +
                    " attempt=" + (reconnectAttempt + 1),
            )

            disconnectRequested = false
            gatt = device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )

            if (gatt == null) {
                handleConnectionFailure(
                    "Android could not create a BLE connection",
                    reconnect,
                )
            }
        } catch (e: SecurityException) {
            reportError("Bluetooth permission was denied")
        } catch (e: Exception) {
            handleConnectionFailure(
                "BLE connection failed: " + (e.message ?: e.javaClass.simpleName),
                reconnect,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cancelReconnect()
        reconnectAttempt = 0
        lastDevice = null
        closeGatt(userRequested = true)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(userRequested: Boolean) {
        if (userRequested) {
            disconnectRequested = true
        }

        clearOperationQueue()

        val current = gatt
        gatt = null
        current?.disconnect()
        current?.close()

        _connected.value = false
        _services.value = emptyList()
        _values.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun requestMtu(mtu: Int = 247) {
        val current = gatt ?: return
        val started = current.requestMtu(mtu)
        appendLog("MTU_REQUEST " + mtu + " started=" + started)
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

            enqueueRead(currentGatt, characteristic)
            true
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

            enqueueNotification(currentGatt, characteristic)
            true
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
        if (!_connected.value) return

        enqueueStandardCharacteristics(currentGatt)
        startNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueStandardCharacteristics(currentGatt: BluetoothGatt) {
        val known = setOf(
            BATTERY_LEVEL_UUID,
            HEART_RATE_MEASUREMENT_UUID,
            SPO2_CONTINUOUS_UUID,
            SPO2_SPOT_CHECK_UUID,
        )

        currentGatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                val uuid = characteristic.uuid.toString().lowercase(Locale.ROOT)
                if (uuid in known) {
                    enqueueReadIfSupported(currentGatt, characteristic)
                    enqueueNotificationIfSupported(currentGatt, characteristic)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueObservedNotifications(currentGatt: BluetoothGatt) {
        currentGatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                if (characteristic.uuid.toString().equals(SERVICE_CHANGED_UUID, ignoreCase = true)) {
                    return@forEach
                }

                val supportsNotify =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val supportsIndicate =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

                if (supportsNotify || supportsIndicate) {
                    enqueueNotification(currentGatt, characteristic)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueReadIfSupported(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            return
        }

        enqueueRead(currentGatt, characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun enqueueNotificationIfSupported(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val supportsNotify =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

        if (supportsNotify || supportsIndicate) {
            enqueueNotification(currentGatt, characteristic)
        }
    }

    private fun enqueueRead(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (currentGatt !== gatt) return

        val key = "read:" + characteristic.uuid
        if (!queuedOperationKeys.add(key)) return

        operationQueue.addLast(
            GattOperation.Read(
                gatt = currentGatt,
                characteristic = characteristic,
            ),
        )
        startNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueNotification(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (currentGatt !== gatt) return

        val supportsNotify =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

        if (!supportsNotify && !supportsIndicate) return

        val cccd = characteristic.descriptors.firstOrNull {
            it.uuid == UUID.fromString(CCCD_UUID)
        }

        if (cccd == null) {
            appendLog("NOTIFY no CCCD " + characteristic.uuid)
            return
        }

        val key = "notify:" + characteristic.uuid
        if (!queuedOperationKeys.add(key)) return

        val value = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        operationQueue.addLast(
            GattOperation.EnableNotification(
                gatt = currentGatt,
                characteristic = characteristic,
                descriptor = cccd,
                value = value,
            ),
        )
        startNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun startNextOperation() {
        if (activeOperation != null || !_connected.value) return

        val currentGatt = gatt ?: return
        if (operationQueue.isEmpty()) return

        val next = operationQueue.removeFirst()
        val key = operationKey(next)

        if (next.gatt !== currentGatt) {
            queuedOperationKeys.remove(key)
            startNextOperation()
            return
        }

        activeOperation = next
        startOperationTimeout()

        try {
            when (next) {
                is GattOperation.Read -> {
                    val started = currentGatt.readCharacteristic(next.characteristic)
                    appendLog(
                        "READ " + next.characteristic.uuid +
                            " started=" + started,
                    )
                    if (!started) finishOperation(key)
                }

                is GattOperation.EnableNotification -> {
                    val localEnabled =
                        currentGatt.setCharacteristicNotification(next.characteristic, true)

                    if (!localEnabled) {
                        appendLog(
                            "NOTIFY local enable failed " +
                                next.characteristic.uuid,
                        )
                        finishOperation(key)
                        return
                    }

                    @Suppress("DEPRECATION")
                    next.descriptor.value = next.value

                    @Suppress("DEPRECATION")
                    val writeStarted = currentGatt.writeDescriptor(next.descriptor)

                    appendLog(
                        "NOTIFY " + next.characteristic.uuid +
                            " descriptorWrite=" + writeStarted,
                    )

                    if (!writeStarted) finishOperation(key)
                }
            }
        } catch (e: SecurityException) {
            reportError("Bluetooth permission was denied")
            finishOperation(key)
        } catch (e: Exception) {
            appendLog(
                "GATT_OP_ERROR " + key + " " +
                    (e.message ?: e.javaClass.simpleName),
            )
            finishOperation(key)
        }
    }

    private fun startOperationTimeout() {
        operationTimeout?.let(handler::removeCallbacks)

        val timeout = Runnable {
            val active = activeOperation ?: return@Runnable
            val key = operationKey(active)
            appendLog("GATT_OP_TIMEOUT " + key)
            finishOperation(key)
        }

        operationTimeout = timeout
        handler.postDelayed(timeout, GATT_OPERATION_TIMEOUT_MS)
    }

    private fun finishOperation(key: String) {
        val active = activeOperation ?: return
        if (operationKey(active) != key) {
            appendLog("GATT_LATE_CALLBACK ignored=" + key)
            return
        }

        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        activeOperation = null
        queuedOperationKeys.remove(key)
        handler.postDelayed({ startNextOperation() }, GATT_OPERATION_GAP_MS)
    }

    private fun operationKey(operation: GattOperation): String = when (operation) {
        is GattOperation.Read -> "read:" + operation.characteristic.uuid
        is GattOperation.EnableNotification -> "notify:" + operation.characteristic.uuid
    }

    private fun clearOperationQueue() {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        activeOperation = null
        operationQueue.clear()
        queuedOperationKeys.clear()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (this@BleGattClient.gatt != gatt) {
                runCatching { gatt.close() }
                appendLog(
                    "STATE stale callback ignored status=" +
                        status + " state=" + newState,
                )
                return
            }

            val stateText = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> newState.toString()
            }

            appendLog(
                "STATE status=" + status +
                    " state=" + stateText,
            )

            if (newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                _connected.value = true
                _error.value = null
                reconnectAttempt = 0

                try {
                    @Suppress("DEPRECATION")
                    val started = gatt.discoverServices()
                    appendLog("DISCOVER started=" + started)

                    if (!started) {
                        reportError("GATT service discovery could not start")
                    }
                } catch (e: SecurityException) {
                    reportError("Bluetooth permission was denied")
                }

                return
            }

            if (newState != BluetoothProfile.STATE_DISCONNECTED) return

            val userRequested = disconnectRequested
            disconnectRequested = false
            _connected.value = false
            _services.value = emptyList()
            _values.value = emptyList()
            clearOperationQueue()
            runCatching { gatt.close() }

            if (this@BleGattClient.gatt == gatt) {
                this@BleGattClient.gatt = null
            }

            if (userRequested) return

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

            appendLog(
                "DISCONNECT_REASON " + reason +
                    " status=" + status,
            )

            if (lastDevice != null && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect(
                    reason + " (status " + status + ")",
                )
            } else {
                reportError(reason + " (status " + status + ")")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@BleGattClient.gatt != gatt) return

            appendLog("SERVICES status=" + status)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError("GATT service discovery failed (status " + status + ")")
                return
            }

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
                            " props=" +
                            propertyNames(characteristic.properties).joinToString(","),
                    )
                }
            }

            reconnectAttempt = 0
            _error.value = null

            enqueueStandardCharacteristics(gatt)
            enqueueObservedNotifications(gatt)

            handler.postDelayed(
                { startNextOperation() },
                DISCOVERY_TO_GATT_OPERATION_DELAY_MS,
            )
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@BleGattClient.gatt != gatt) return

            val key = "read:" + characteristic.uuid

            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                recordValue(gatt, characteristic, value, "READ")
            } else {
                appendLog(
                    "READ_RESULT " + characteristic.uuid +
                        " status=" + status,
                )
            }

            finishOperation(key)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (this@BleGattClient.gatt != gatt) return

            val key = "read:" + characteristic.uuid

            if (status == BluetoothGatt.GATT_SUCCESS) {
                recordValue(gatt, characteristic, value, "READ")
            } else {
                appendLog(
                    "READ_RESULT " + characteristic.uuid +
                        " status=" + status,
                )
            }

            finishOperation(key)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (this@BleGattClient.gatt != gatt) return

            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()

            recordValue(gatt, characteristic, value, "NOTIFICATION")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@BleGattClient.gatt != gatt) return
            recordValue(gatt, characteristic, value, "NOTIFICATION")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@BleGattClient.gatt != gatt) return

            val key = "notify:" + descriptor.characteristic.uuid
            appendLog(
                "CCCD " + descriptor.characteristic.uuid +
                    " status=" + status,
            )

            finishOperation(key)
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int,
        ) {
            appendLog("MTU mtu=" + mtu + " status=" + status)
        }
    }

    private fun scheduleReconnect(reason: String) {
        cancelReconnect()

        val device = lastDevice ?: run {
            reportError(reason)
            return
        }

        reconnectAttempt += 1
        val message =
            reason +
                "; reconnecting " +
                reconnectAttempt +
                "/" +
                MAX_RECONNECT_ATTEMPTS

        _error.value = message
        appendLog("RECONNECT " + message)

        val runnable = Runnable {
            if (disconnectRequested) return@Runnable
            if (lastDevice?.address != device.address) return@Runnable
            openGatt(device, reconnect = true)
        }

        reconnectRunnable = runnable
        handler.postDelayed(runnable, RECONNECT_DELAY_MS)
    }

    private fun handleConnectionFailure(
        message: String,
        reconnect: Boolean,
    ) {
        gatt?.close()
        gatt = null

        if (reconnect && lastDevice != null && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            scheduleReconnect(message)
        } else {
            reportError(message)
        }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
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
        _values.value = (existing + item).takeLast(120)

        appendLog(
            source + " " + characteristic.uuid +
                " value=" + item.hex +
                (item.decoded?.let { " decoded=" + it } ?: ""),
        )
    }

    private fun decodeStandardValue(
        uuid: UUID,
        value: ByteArray,
    ): String? {
        val id = uuid.toString().lowercase(Locale.ROOT)

        if (id == BATTERY_LEVEL_UUID) {
            val percent = value.firstOrNull()?.toInt()?.and(0xFF)
            return percent?.takeIf { it in 0..100 }?.let {
                "Battery " + it + "%"
            }
        }

        if (id == HEART_RATE_MEASUREMENT_UUID && value.isNotEmpty()) {
            val flags = value[0].toInt() and 0xFF
            val index = 1

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

        if (id == SPO2_CONTINUOUS_UUID || id == SPO2_SPOT_CHECK_UUID) {
            return decodePulseOximeter(value)
        }

        return if (isStandardTextCharacteristic(id)) {
            ascii(value).takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun decodePulseOximeter(value: ByteArray): String? {
        if (value.size < 3) return null

        val flags = value[0].toInt() and 0xFF
        val firstMeasurement = ieee11073SFloat(
            value[1].toInt() and 0xFF,
            value[2].toInt() and 0xFF,
        ) ?: return null

        if (!firstMeasurement.isFinite() || firstMeasurement !in 0.0..100.0) {
            return null
        }

        appendLog(
            "SPO2 flags=" + flags +
                " value=" + firstMeasurement,
        )

        return "SpO₂ " +
            String.format(Locale.US, "%.0f", firstMeasurement) +
            "%"
    }

    private fun ieee11073SFloat(
        low: Int,
        high: Int,
    ): Double? {
        val raw = (high shl 8) or low

        val exponent = ((raw shr 12) and 0x0F).let {
            if (it and 0x08 != 0) it - 16 else it
        }

        val mantissaRaw = raw and 0x0FFF
        val mantissa = if (mantissaRaw and 0x0800 != 0) {
            mantissaRaw - 0x1000
        } else {
            mantissaRaw
        }

        if (mantissa == 0x07FF || mantissa == 0x0800) return Double.NaN
        if (mantissa == 0x07FE) return Double.POSITIVE_INFINITY
        if (mantissa == 0x0802) return Double.NEGATIVE_INFINITY

        return mantissa * Math.pow(10.0, exponent.toDouble())
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
        bytes.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

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
        _logs.value = (_logs.value + line).takeLast(500)
    }

    private companion object {
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
        const val SPO2_SPOT_CHECK_UUID = "00002a5e-0000-1000-8000-00805f9b34fb"
        const val SPO2_CONTINUOUS_UUID = "00002a5f-0000-1000-8000-00805f9b34fb"
        const val SERVICE_CHANGED_UUID = "00002a05-0000-1000-8000-00805f9b34fb"

        const val GATT_CONN_TERMINATE_PEER_USER = 19
        const val GATT_CONN_TERMINATE_LOCAL_HOST = 22
        const val GATT_CONN_TIMEOUT = 8

        const val MAX_RECONNECT_ATTEMPTS = 2
        const val RECONNECT_DELAY_MS = 1_500L
        const val GATT_OPERATION_TIMEOUT_MS = 4_000L
        const val GATT_OPERATION_GAP_MS = 100L
        const val DISCOVERY_TO_GATT_OPERATION_DELAY_MS = 300L
    }
}
