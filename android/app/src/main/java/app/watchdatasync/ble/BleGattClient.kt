package app.watchdatasync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import app.watchdatasync.model.DailyActivitySummary
import app.watchdatasync.model.GattCharacteristic
import app.watchdatasync.model.GattService
import app.watchdatasync.model.GattValue
import app.watchdatasync.model.HeartRateHistorySample
import app.watchdatasync.model.Spo2HistorySample
import app.watchdatasync.protocol.FastrackProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class BleGattClient(private val context: Context) {
    private var gatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var disconnectRequested = false
    private var reconnectAttempt = 0
    private var matchedVendorProtocol = false
    private val fastrackProtocol = FastrackProtocol()

    private val handler = Handler(Looper.getMainLooper())
    private val capturePrefs = context.getSharedPreferences(
        CAPTURE_PREFS,
        Context.MODE_PRIVATE,
    )
    private var operationTimeout: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var capturePersistRunnable: Runnable? = null

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

        data class Write(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeWithoutResponse: Boolean,
            val settleDelayMs: Long,
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

    private val _liveHeartRate = MutableStateFlow<Int?>(null)
    val liveHeartRate: StateFlow<Int?> = _liveHeartRate.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val dataPrefs = context.getSharedPreferences(
        HEALTH_DATA_PREFS,
        Context.MODE_PRIVATE,
    )
    private val _heartRateHistory = MutableStateFlow<List<HeartRateHistorySample>>(emptyList())
    val heartRateHistory: StateFlow<List<HeartRateHistorySample>> = _heartRateHistory.asStateFlow()
    private val _spo2History = MutableStateFlow<List<Spo2HistorySample>>(emptyList())
    val spo2History: StateFlow<List<Spo2HistorySample>> = _spo2History.asStateFlow()
    private val _dailyActivity = MutableStateFlow<DailyActivitySummary?>(null)
    val dailyActivity: StateFlow<DailyActivitySummary?> = _dailyActivity.asStateFlow()
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()
    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private var syncFinishRunnable: Runnable? = null
    private var syncRequested = false

    init {
        _values.value = loadPersistedCapture()
        _heartRateHistory.value = loadHeartRateHistory()
        _spo2History.value = loadSpo2History()
        _dailyActivity.value = loadDailyActivity()
        _batteryPercent.value = dataPrefs.getInt(KEY_BATTERY, -1).takeIf { it in 0..100 }
        _lastSyncAt.value = dataPrefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        cancelReconnect()
        reconnectAttempt = 0
        closeGatt(userRequested = true)

        disconnectRequested = false
        lastDevice = device
        _error.value = null
        _services.value = emptyList()
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
        _liveHeartRate.value = null
        matchedVendorProtocol = false
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
        syncNow()
    }

    @SuppressLint("MissingPermission")
    fun syncNow() {
        val currentGatt = gatt ?: return
        if (!_connected.value || !matchedVendorProtocol || syncRequested) return
        enqueueFt38093Sync(currentGatt)
    }

    @SuppressLint("MissingPermission")
    private fun enqueueStandardCharacteristics(currentGatt: BluetoothGatt) {
        val known = setOf(
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

    @SuppressLint("MissingPermission")
    private fun enqueueWrite(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeWithoutResponse: Boolean,
        settleDelayMs: Long,
        label: String,
    ) {
        if (currentGatt !== gatt) return
        val key = "write:" + characteristic.uuid + ":" + hex(value)
        if (!queuedOperationKeys.add(key)) return
        appendLog("SYNC_QUEUE " + label + " char=" + characteristic.uuid + " bytes=" + hex(value))
        operationQueue.addLast(
            GattOperation.Write(
                gatt = currentGatt,
                characteristic = characteristic,
                value = value,
                writeWithoutResponse = writeWithoutResponse,
                settleDelayMs = settleDelayMs,
            ),
        )
        startNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueFt38093Sync(currentGatt: BluetoothGatt) {
        val characteristics = currentGatt.services.flatMap { it.characteristics }
        val channel1 = characteristics.firstOrNull { it.uuid.toString().equals(CHAR_33F1_UUID, ignoreCase = true) }
        val channel2 = characteristics.firstOrNull { it.uuid.toString().equals(CHAR_34F1_UUID, ignoreCase = true) }
        if (channel1 == null || channel2 == null) {
            appendLog("SYNC unavailable: FT_38093 write channels are missing")
            return
        }

        syncRequested = true
        _syncing.value = true
        _error.value = null
        appendLog("SYNC_START FT_38093 automatic health/history sync")

        val now = Calendar.getInstance()
        fastrackProtocol.buildAutomaticSyncCommands(now).forEach { command ->
            val characteristic = when (command.characteristicUuid.lowercase(Locale.ROOT)) {
                CHAR_33F1_UUID -> channel1
                CHAR_34F1_UUID -> channel2
                else -> null
            } ?: return@forEach

            val supportsWrite = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val supportsWriteNr = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            if (!supportsWrite && !supportsWriteNr) {
                appendLog("SYNC skip " + command.label + " unsupported=" + characteristic.uuid)
                return@forEach
            }

            enqueueWrite(
                currentGatt = currentGatt,
                characteristic = characteristic,
                value = command.payload,
                writeWithoutResponse = command.writeWithoutResponse || (!supportsWrite && supportsWriteNr),
                settleDelayMs = command.settleDelayMs,
                label = command.label,
            )
        }

        syncFinishRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            syncRequested = false
            _syncing.value = false
            val stamp = System.currentTimeMillis()
            _lastSyncAt.value = stamp
            dataPrefs.edit().putLong(KEY_LAST_SYNC, stamp).apply()
            appendLog("SYNC_COMPLETE FT_38093")
        }
        syncFinishRunnable = runnable
        handler.postDelayed(runnable, SYNC_COMPLETE_DELAY_MS)
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

        val operationGatt = when (next) {
            is GattOperation.Read -> next.gatt
            is GattOperation.EnableNotification -> next.gatt
            is GattOperation.Write -> next.gatt
        }

        if (operationGatt !== currentGatt) {
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

                is GattOperation.Write -> {
                    @Suppress("DEPRECATION")
                    next.characteristic.writeType =
                        if (next.writeWithoutResponse) {
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        } else {
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }
                    @Suppress("DEPRECATION")
                    next.characteristic.value = next.value
                    @Suppress("DEPRECATION")
                    val started = currentGatt.writeCharacteristic(next.characteristic)
                    appendLog(
                        "WRITE " + next.characteristic.uuid +
                            " started=" + started +
                            " type=" + if (next.writeWithoutResponse) "NR" else "R",
                    )
                    if (!started || next.writeWithoutResponse) finishOperation(key)
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
        val delay = when (active) {
            is GattOperation.Write -> active.settleDelayMs
            else -> GATT_OPERATION_GAP_MS
        }
        handler.postDelayed({ startNextOperation() }, delay)
    }

    private fun operationKey(operation: GattOperation): String = when (operation) {
        is GattOperation.Read -> "read:" + operation.characteristic.uuid
        is GattOperation.EnableNotification -> "notify:" + operation.characteristic.uuid
        is GattOperation.Write -> "write:" + operation.characteristic.uuid + ":" + hex(operation.value)
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
            _liveHeartRate.value = null
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

            val discoveredServiceUuids = gatt.services
                .map { it.uuid.toString() }
                .toSet()
            val discoveredCharacteristicUuids = gatt.services
                .flatMap { it.characteristics }
                .map { it.uuid.toString() }
                .toSet()

            matchedVendorProtocol = fastrackProtocol.matchesGatt(
                serviceUuids = discoveredServiceUuids,
                characteristicUuids = discoveredCharacteristicUuids,
            )

            appendLog(
                if (matchedVendorProtocol) {
                    "PROTOCOL Fastrack FT_38093 live channel detected"
                } else {
                    "PROTOCOL standard/unknown GATT layout"
                },
            )

            reconnectAttempt = 0
            _error.value = null

            enqueueStandardCharacteristics(gatt)
            enqueueObservedNotifications(gatt)

            handler.postDelayed(
                {
                    startNextOperation()
                    if (matchedVendorProtocol) {
                        handler.postDelayed({ syncNow() }, SYNC_START_DELAY_MS)
                    }
                },
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

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@BleGattClient.gatt != gatt) return
            val active = activeOperation
            if (active is GattOperation.Write &&
                active.characteristic.uuid == characteristic.uuid
            ) {
                appendLog("WRITE_RESULT " + characteristic.uuid + " status=" + status)
                finishOperation(operationKey(active))
            }
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

        decodeFastrackSyncPacket(characteristic.uuid.toString(), value)

        val serviceUuid = gatt.services.firstOrNull { service ->
            service.characteristics.any { it.uuid == characteristic.uuid }
        }?.uuid?.toString() ?: "unknown"

        val uuid = characteristic.uuid.toString().lowercase(Locale.ROOT)
        if (uuid == BATTERY_LEVEL_UUID) {
            return
        }

        val standardDecoded = decodeStandardValue(characteristic.uuid, value)
        val vendorDecoded =
            if (matchedVendorProtocol) {
                fastrackProtocol.decode(
                    characteristicUuid = characteristic.uuid.toString(),
                    packet = value,
                )
            } else {
                null
            }

        val decoded = standardDecoded ?: vendorDecoded

        if (isHeartRatePacket(characteristic, value, decoded)) {
            _liveHeartRate.value = extractHeartRate(decoded)
            return
        }

        val item = GattValue(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristic.uuid.toString(),
            timestamp = timestamp(),
            hex = hex(value),
            ascii = ascii(value),
            decoded = decoded ?: diagnosticByteSummary(value),
        )

        _values.value = (_values.value + item)
            .filter { it.capturedAt >= System.currentTimeMillis() - CAPTURE_RETENTION_MS }
            .takeLast(MAX_CAPTURE_VALUES)

        schedulePersistCapture()
        appendLog(
            source + " " + characteristic.uuid +
                " value=" + item.hex +
                (item.decoded?.let { " decoded=" + it } ?: ""),
        )
    }

    private fun decodeFastrackSyncPacket(
        characteristicUuid: String,
        value: ByteArray,
    ) {
        if (!matchedVendorProtocol || value.isEmpty()) return
        if (!characteristicUuid.equals(CHAR_33F2_UUID, ignoreCase = true) &&
            !characteristicUuid.equals(CHAR_34F2_UUID, ignoreCase = true)
        ) return

        when (value[0].toInt() and 0xFF) {
            0xA1 -> {
                val serial = value.copyOfRange(1, value.size).toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
                if (serial.isNotBlank()) appendLog("SYNC_DATA serial=" + serial)
            }
            0xA2 -> {
                val battery = value.getOrNull(1)?.toInt()?.and(0xFF)
                if (battery != null && battery in 0..100) {
                    _batteryPercent.value = battery
                    dataPrefs.edit().putInt(KEY_BATTERY, battery).apply()
                    appendLog("SYNC_DATA battery=" + battery + "%")
                }
            }
            0x26 -> {
                if (value.size >= 10 && (value[1].toInt() and 0xFF) == 0x01) {
                    val summary = DailyActivitySummary(
                        epochMillis = System.currentTimeMillis(),
                        steps = leU16(value, 3),
                        calories = leU16(value, 5),
                        distanceMeters = leU16(value, 7),
                        activeMinutes = value[9].toInt() and 0xFF,
                    )
                    _dailyActivity.value = summary
                    persistDailyActivity(summary)
                    appendLog(
                        "SYNC_DATA activity=" + summary.steps + " steps, " +
                            summary.calories + " kcal, " +
                            summary.distanceMeters + " m, " +
                            summary.activeMinutes + " min",
                    )
                }
            }
            0xF7 -> decodeHeartRateHistory(value)
            0x34 -> decodeSpo2History(value)
            0x32, 0xCB, 0xB1, 0xB2 -> appendLog("SYNC_DATA passive frame " + hex(value))
        }
    }

    private fun decodeHeartRateHistory(value: ByteArray) {
        if (value.size < 7) return
        val start = if ((value[1].toInt() and 0xFF) == 0xFA) 2 else 1
        if (value.size < start + 5) return
        val year = ((value[start].toInt() and 0xFF) shl 8) or (value[start + 1].toInt() and 0xFF)
        val month = value[start + 2].toInt() and 0xFF
        val day = value[start + 3].toInt() and 0xFF
        val page = value[start + 4].toInt() and 0xFF
        if (year !in 2020..2100 || month !in 1..12 || day !in 1..31 || page !in 0..23) return

        val calendar = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, page, 0, 0)
        }
        val samples = value.drop(start + 5).mapIndexedNotNull { index, raw ->
            val bpm = raw.toInt() and 0xFF
            if (bpm in 30..220) HeartRateHistorySample(
                epochMillis = calendar.timeInMillis + index * 5L * 60L * 1000L,
                bpm = bpm,
            ) else null
        }
        if (samples.isNotEmpty()) {
            _heartRateHistory.value = mergeHeartRateHistory(_heartRateHistory.value, samples)
            persistHeartRateHistory()
            appendLog("SYNC_DATA heart-rate history page=" + page + " samples=" + samples.size)
        }
    }

    private fun decodeSpo2History(value: ByteArray) {
        if (value.size < 8) return
        val hasFa = (value[1].toInt() and 0xFF) == 0xFA
        val yearIndex = if (hasFa) 2 else 1
        if (yearIndex + 4 >= value.size) return
        val year = ((value[yearIndex].toInt() and 0xFF) shl 8) or (value[yearIndex + 1].toInt() and 0xFF)
        val month = value[yearIndex + 2].toInt() and 0xFF
        val day = value[yearIndex + 3].toInt() and 0xFF
        val hour = value[yearIndex + 4].toInt() and 0xFF
        val percent = value.last().toInt() and 0xFF
        if (year !in 2020..2100 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || percent !in 70..100) return

        val calendar = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }
        val sample = Spo2HistorySample(calendar.timeInMillis, percent)
        _spo2History.value = mergeSpo2History(_spo2History.value, listOf(sample))
        persistSpo2History()
        appendLog(
            "SYNC_DATA SpO2=" + percent + "% at " +
                year + "-" + month.toString().padStart(2, '0') + "-" +
                day.toString().padStart(2, '0') + " " +
                hour.toString().padStart(2, '0') + ":00",
        )
    }

    private fun leU16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)

    private fun mergeHeartRateHistory(existing: List<HeartRateHistorySample>, incoming: List<HeartRateHistorySample>): List<HeartRateHistorySample> =
        (existing + incoming).distinctBy { it.epochMillis }.filter {
            it.epochMillis >= System.currentTimeMillis() - HEART_RATE_RETENTION_MS
        }.sortedBy { it.epochMillis }.takeLast(MAX_HEART_RATE_HISTORY)

    private fun mergeSpo2History(existing: List<Spo2HistorySample>, incoming: List<Spo2HistorySample>): List<Spo2HistorySample> =
        (existing + incoming).distinctBy { it.epochMillis }.filter {
            it.epochMillis >= System.currentTimeMillis() - SPO2_RETENTION_MS
        }.sortedBy { it.epochMillis }.takeLast(MAX_SPO2_HISTORY)

    private fun persistHeartRateHistory() {
        val array = JSONArray()
        _heartRateHistory.value.forEach { item -> array.put(JSONObject().apply {
            put("time", item.epochMillis); put("bpm", item.bpm)
        }) }
        dataPrefs.edit().putString(KEY_HEART_RATE_HISTORY, array.toString()).apply()
    }

    private fun persistSpo2History() {
        val array = JSONArray()
        _spo2History.value.forEach { item -> array.put(JSONObject().apply {
            put("time", item.epochMillis); put("spo2", item.percent)
        }) }
        dataPrefs.edit().putString(KEY_SPO2_HISTORY, array.toString()).apply()
    }

    private fun persistDailyActivity(summary: DailyActivitySummary) {
        dataPrefs.edit()
            .putLong(KEY_ACTIVITY_TIME, summary.epochMillis)
            .putInt(KEY_STEPS, summary.steps)
            .putInt(KEY_CALORIES, summary.calories)
            .putInt(KEY_DISTANCE, summary.distanceMeters)
            .putInt(KEY_ACTIVE_MINUTES, summary.activeMinutes)
            .apply()
    }

    private fun loadHeartRateHistory(): List<HeartRateHistorySample> {
        val raw = dataPrefs.getString(KEY_HEART_RATE_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val cutoff = System.currentTimeMillis() - HEART_RATE_RETENTION_MS
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val time = item.optLong("time", 0L)
                val bpm = item.optInt("bpm", 0)
                if (time >= cutoff && bpm in 30..220) add(HeartRateHistorySample(time, bpm))
            }
        }.takeLast(MAX_HEART_RATE_HISTORY)
    }

    private fun loadSpo2History(): List<Spo2HistorySample> {
        val raw = dataPrefs.getString(KEY_SPO2_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val cutoff = System.currentTimeMillis() - SPO2_RETENTION_MS
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val time = item.optLong("time", 0L)
                val percent = item.optInt("spo2", 0)
                if (time >= cutoff && percent in 70..100) add(Spo2HistorySample(time, percent))
            }
        }.takeLast(MAX_SPO2_HISTORY)
    }

    private fun loadDailyActivity(): DailyActivitySummary? {
        val time = dataPrefs.getLong(KEY_ACTIVITY_TIME, 0L)
        if (time <= 0L) return null
        return DailyActivitySummary(
            epochMillis = time,
            steps = dataPrefs.getInt(KEY_STEPS, 0),
            calories = dataPrefs.getInt(KEY_CALORIES, 0),
            distanceMeters = dataPrefs.getInt(KEY_DISTANCE, 0),
            activeMinutes = dataPrefs.getInt(KEY_ACTIVE_MINUTES, 0),
        )
    }

    private fun isHeartRatePacket(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        decoded: String?,
    ): Boolean {
        val uuid = characteristic.uuid.toString().lowercase(Locale.ROOT)
        if (uuid == HEART_RATE_MEASUREMENT_UUID) return true

        return uuid == VENDOR_HEART_RATE_UUID &&
            value.size >= 3 &&
            (value[0].toInt() and 0xFF) == 0xE5 &&
            (value[1].toInt() and 0xFF) == 0x11 &&
            (value[2].toInt() and 0xFF) == 0x00
    }

    private fun extractHeartRate(decoded: String?): Int? =
        Regex("Heart rate (\\d+) bpm")
            .find(decoded.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun schedulePersistCapture() {
        capturePersistRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable { persistCapture() }
        capturePersistRunnable = runnable
        handler.postDelayed(runnable, CAPTURE_PERSIST_DELAY_MS)
    }

    private fun persistCapture() {
        capturePersistRunnable = null
        val cutoff = System.currentTimeMillis() - CAPTURE_RETENTION_MS
        val current = _values.value
            .filter { it.capturedAt >= cutoff }
            .takeLast(MAX_CAPTURE_VALUES)

        val array = JSONArray()
        current.forEach { value ->
            array.put(
                JSONObject().apply {
                    put("service", value.serviceUuid)
                    put("characteristic", value.characteristicUuid)
                    put("timestamp", value.timestamp)
                    put("hex", value.hex)
                    put("ascii", value.ascii)
                    put("decoded", value.decoded)
                    put("capturedAt", value.capturedAt)
                },
            )
        }

        capturePrefs.edit()
            .putString(CAPTURE_KEY, array.toString())
            .apply()
    }

    private fun loadPersistedCapture(): List<GattValue> {
        val cutoff = System.currentTimeMillis() - CAPTURE_RETENTION_MS
        val raw = capturePrefs.getString(CAPTURE_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val objectValue = array.getJSONObject(index)
                    val capturedAt = objectValue.optLong("capturedAt", 0L)
                    if (capturedAt < cutoff) continue
                    add(
                        GattValue(
                            serviceUuid = objectValue.optString("service"),
                            characteristicUuid = objectValue.optString("characteristic"),
                            timestamp = objectValue.optString("timestamp"),
                            hex = objectValue.optString("hex"),
                            ascii = objectValue.optString("ascii"),
                            decoded = objectValue.optString("decoded").takeIf { it != "null" },
                            capturedAt = capturedAt,
                        ),
                    )
                }
            }.takeLast(MAX_CAPTURE_VALUES)
        }.getOrDefault(emptyList())
    }

    private fun diagnosticByteSummary(value: ByteArray): String {
        if (value.isEmpty()) return "Unknown packet • 0 bytes"

        return "Unknown packet • " +
            value.size +
            " bytes • HEX=" +
            hex(value) +
            " • ASCII=" +
            ascii(value)
    }


    private fun decodeStandardValue(
        uuid: UUID,
        value: ByteArray,
    ): String? {
        val id = uuid.toString().lowercase(Locale.ROOT)

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

    fun clearCapture() {
        capturePersistRunnable?.let(handler::removeCallbacks)
        capturePersistRunnable = null
        _values.value = emptyList()
        _logs.value = emptyList()
        capturePrefs.edit().remove(CAPTURE_KEY).apply()
    }

    fun markCapture(label: String) {
        val clean = label.trim().take(40)
        if (clean.isBlank()) return
        val stamp = timestamp()
        appendLog("TEST_MARKER " + stamp + " • " + clean)
    }

    private fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(5_000)
    }

    private companion object {
        const val CAPTURE_PREFS = "watch_capture"
        const val CAPTURE_KEY = "packets"
        const val HEALTH_DATA_PREFS = "watch_health_data"
        const val KEY_HEART_RATE_HISTORY = "heart_rate_history"
        const val KEY_SPO2_HISTORY = "spo2_history"
        const val KEY_ACTIVITY_TIME = "daily_activity_time"
        const val KEY_STEPS = "daily_steps"
        const val KEY_CALORIES = "daily_calories"
        const val KEY_DISTANCE = "daily_distance"
        const val KEY_ACTIVE_MINUTES = "daily_active_minutes"
        const val KEY_BATTERY = "watch_battery"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val HEART_RATE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        const val SPO2_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        const val MAX_HEART_RATE_HISTORY = 20_000
        const val MAX_SPO2_HISTORY = 5_000
        const val SYNC_COMPLETE_DELAY_MS = 20_000L
        const val SYNC_START_DELAY_MS = 1_000L
        const val CAPTURE_RETENTION_MS = 24L * 60L * 60L * 1000L
        const val CAPTURE_PERSIST_DELAY_MS = 2_000L
        const val MAX_CAPTURE_VALUES = 20_000
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
        const val VENDOR_HEART_RATE_UUID = "000033f2-0000-1000-8000-00805f9b34fb"
        const val CHAR_33F1_UUID = "000033f1-0000-1000-8000-00805f9b34fb"
        const val CHAR_34F1_UUID = "000034f1-0000-1000-8000-00805f9b34fb"
        const val CHAR_34F2_UUID = "000034f2-0000-1000-8000-00805f9b34fb"
        const val SPO2_SPOT_CHECK_UUID = "00002a5e-0000-1000-8000-00805f9b34fb"
        const val SPO2_CONTINUOUS_UUID = "00002a5f-0000-1000-8000-00805f9b34fb"
        const val SERVICE_CHANGED_UUID = "00002a05-0000-1000-8000-00805f9b34fb"

        const val GATT_CONN_TERMINATE_PEER_USER = 19
        const val GATT_CONN_TERMINATE_LOCAL_HOST = 22
        const val GATT_CONN_TIMEOUT = 8

        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RECONNECT_DELAY_MS = 2_000L
        const val GATT_OPERATION_TIMEOUT_MS = 4_000L
        const val GATT_OPERATION_GAP_MS = 100L
        const val DISCOVERY_TO_GATT_OPERATION_DELAY_MS = 300L
    }
}
