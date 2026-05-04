package com.example.eli_ble_gatt_server

import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.EventChannel

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class EliBleGattServerService : Service() {

    companion object {
        private const val TAG = "BluetoothGattService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "BluetoothGattServiceChannel"

        // 16-bit UUIDs
        val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        lateinit var SERVICE_UUID: UUID
        lateinit var CHARACTERISTIC_UUID: UUID
        lateinit var DEVICE_NAME: String

        var payload: Map<*, *>? = null
        var isRunning = false

        // Static status mirrors — updated by the service instance so the plugin can query them without binding
        var serverActive = false
        var advertisingActive = false
        var connectedDeviceCount = 0

        fun getStatusMap(): Map<String, Any?> = mapOf(
            "isActive" to serverActive,
            "advertising" to advertisingActive,
            "connectedDevices" to connectedDeviceCount,
            "deviceName" to if (::DEVICE_NAME.isInitialized) DEVICE_NAME else null,
            "serviceUuid" to if (::SERVICE_UUID.isInitialized) SERVICE_UUID.toString() else null,
            "characteristicUuid" to if (::CHARACTERISTIC_UUID.isInitialized) CHARACTERISTIC_UUID.toString() else null
        )

        fun configure(
            serviceUuid: String,
            characteristicUuid: String,
            deviceName: String?,
            payloadFromFlutter: Map<*, *>?
        ) {
            SERVICE_UUID = UUID.fromString(serviceUuid)
            CHARACTERISTIC_UUID = UUID.fromString(characteristicUuid)
            DEVICE_NAME = deviceName ?: "BLE-Server-Flutter"
            payload = payloadFromFlutter
        }

        // Constants for service types (numeric values)
        private const val SERVICE_TYPE_PRIMARY = 0

        var eventSink: EventChannel.EventSink? = null
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var originalDeviceName: String? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): EliBleGattServerService = this@EliBleGattServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // State variables
    private var isAdvertisingActive = false
    private var isServerActive = false
    private var connectedDevices = mutableListOf<String>()

    // Callbacks for state changes
    private var onServerStateChanged: ((Boolean) -> Unit)? = null
    private var onConnectionChanged: ((List<String>) -> Unit)? = null

    private val connectedGattDevices = mutableSetOf<BluetoothDevice>()
    private var onBleMessageReceived: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Bluetooth GATT service created")

        createNotificationChannel()
        startForegroundServiceWithType()
        isRunning = true

        // Save original device name
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            originalDeviceName = bluetoothAdapter?.name
            Log.d(TAG, "Original device name saved: $originalDeviceName")
        }

        initializeBluetooth()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth GATT Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for Bluetooth GATT service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessageToConnectedDevices(message: String) {
        if (connectedGattDevices.isEmpty()) {
            Log.w(TAG, "No connected devices")
            return
        }

        val service = bluetoothGattServer?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        // BLE REAL: 20 bytes per NOTIFY
        val chunkSize = 20
        val payload = message.toByteArray(Charsets.UTF_8)

        Log.d(TAG, "Sending plain TEXT (${payload.size} bytes) in chunks of 20")

        var offset = 0

        while (offset < payload.size) {
            val size = minOf(chunkSize, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + size)

            characteristic.value = chunk

            connectedGattDevices.forEach { device ->
                bluetoothGattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false // NOTIFY
                )
            }

            offset += size
            mainHandler.post {
                eventSink?.success(
                    mapOf(
                        "type" to "ble_tx",
                        "chunk_size" to size,
                        "offset" to offset,
                        "total" to payload.size
                    )
                )
            }

            // Add delay
            Thread.sleep(30)
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundServiceWithType() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12-13
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    // Android 10-11
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "Foreground service started with appropriate type")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            // Android 9 and earlier
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth GATT Active")
            .setContentText("Device: ")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun initializeBluetooth() {
        Log.i(TAG, "Starting Bluetooth...")

        try {
            bluetoothManager = getSystemService(BluetoothManager::class.java)
            bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth cannot be initialized")
                updateServerState(false)
                return
            }

            if (!bluetoothAdapter!!.isEnabled) {
                Log.e(TAG, "Bluetooth is disabled")
                updateServerState(false)
                return
            }

            bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

            if (bluetoothLeAdvertiser == null) {
                Log.e(TAG, "Bluetooth LE Advertising not supported")
                updateServerState(false)
                return
            }

            setupGattServer()
            startAdvertising()

        } catch (e: SecurityException) {
            Log.e(TAG, "Security breach: ${e.message}")
            updateServerState(false)
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth error: ${e.message}")
            updateServerState(false)
        }
    }

    private fun updateServerState(isActive: Boolean) {
        val wasActive = isServerActive
        isServerActive = isActive
        isAdvertisingActive = isActive
        serverActive = isActive
        advertisingActive = isActive

        if (wasActive != isActive) {
            Log.i(TAG, if (isActive) "Server active" else "Server inactive")

            // Notify state change
            onServerStateChanged?.invoke(isActive)

            // Update notification
            updateNotification(isActive)
        }
    }

    private fun updateNotification(isActive: Boolean) {
        val notification = if (isActive) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BLE server active")
                .setContentText("$ - ${connectedDevices.size} device(s) connected")
                .setSmallIcon(R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BLE server inactive")
                .setContentText("Server stopped")
                .setSmallIcon(R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .build()
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    // Set callback for server state changes
    fun setServerStateCallback(callback: (Boolean) -> Unit) {
        onServerStateChanged = callback
        // Notify current state immediately
        callback(isServerActive)
    }

    // Set callback for connection changes
    fun setConnectionCallback(callback: (List<String>) -> Unit) {
        onConnectionChanged = callback
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        Log.i(TAG, "Setting up GATT...")

        try {
            bluetoothGattServer = bluetoothManager?.openGattServer(this, gattServerCallback)

            if (bluetoothGattServer == null) {
                Log.e(TAG, "Could not open GATT server")
                return
            }

            // Use full name
            val service = BluetoothGattService(SERVICE_UUID, SERVICE_TYPE_PRIMARY)

            // Create read/write/notify characteristic
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // Create descriptor for notifications
            val descriptor = BluetoothGattDescriptor(
                DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
            )
            characteristic.addDescriptor(descriptor)
            service.addCharacteristic(characteristic)

            // Add service to server
            val success = bluetoothGattServer?.addService(service)

            if (success == true) {
                Log.i(TAG, "GATT service configured correctly: $SERVICE_UUID")
            } else {
                Log.e(TAG, "Error adding GATT service")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security error configuring GATT: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring GATT: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        Log.i(TAG, "Starting advertising...")

        bluetoothLeAdvertiser?.let { advertiser ->
            try {
                // Configure device name
                try {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // Save original name
                        if (originalDeviceName == null) {
                            originalDeviceName = bluetoothAdapter?.name
                        }

                        // Change device name
                        val setNameSuccess = bluetoothAdapter?.setName(DEVICE_NAME)
                        Log.i(TAG, if (setNameSuccess == true)
                            "Name changed to: $DEVICE_NAME"
                        else "Could not change device name"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not change device name: ${e.message}")
                }

                // Advertising settings
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0) // 0 = no timeout
                    .build()

                // Advertising data
                val advertiseData = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()

                // Scan response data
                val scanResponse = AdvertiseData.Builder()
                    .addManufacturerData(0xFFFF, "BLE_GATT_SERVER".toByteArray())
                    .build()

                // Start advertising
                advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
                Log.i(TAG, "Advertising started")

            } catch (e: SecurityException) {
                Log.e(TAG, "Security error in advertising: ${e.message}")
                updateServerState(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error in advertising: ${e.message}")
                updateServerState(false)
            }
        } ?: run {
            Log.e(TAG, "BluetoothLeAdvertiser not available")
            updateServerState(false)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        Log.i(TAG, "Stopping advertising...")

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertisingActive = false
            Log.i(TAG, "Advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.i(TAG, "Advertising started successfully")
            Log.i(TAG, "Device visible as: ")
            Log.i(TAG, "Mode: ${settingsInEffect.mode}, Power: ${settingsInEffect.txPowerLevel}")
            updateServerState(true)
            mainHandler.post {
                eventSink?.success(
                    mapOf(
                        "type" to "advertising",
                        "status" to "started"
                    )
                )
            }
            emitServerInfo()
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN_ERROR: $errorCode"
            }
            Log.e(TAG, "Error starting advertising: $errorMessage")
            updateServerState(false)
            mainHandler.post {
                eventSink?.success(
                    mapOf(
                        "type" to "advertising",
                        "status" to "error",
                        "code" to errorCode
                    )
                )
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (device == null) return

            val deviceAddress = device?.address ?: "unknown"
            val deviceName = device?.name ?: "no name"
            val state = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Add device to connected list
                    if (!connectedDevices.contains(deviceAddress)) {
                        connectedDevices.add(deviceAddress)
                        connectedGattDevices.add(device)
                        connectedDeviceCount = connectedDevices.size
                        onConnectionChanged?.invoke(connectedDevices.toList())
                    }

                    "Connected"
                    mainHandler.post {
                        eventSink?.success(
                            mapOf(
                                "type" to "device_connected",
                                "address" to device.address,
                                "name" to (device.name ?: "unknown"),
                                "total" to connectedDevices.size
                            )
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Remove device from list
                    connectedDevices.remove(deviceAddress)
                    connectedGattDevices.remove(device)
                    connectedDeviceCount = connectedDevices.size
                    onConnectionChanged?.invoke(connectedDevices.toList())
                    "Disconnected"
                    mainHandler.post {
                        eventSink?.success(
                            mapOf(
                                "type" to "device_disconnected",
                                "address" to device.address,
                                "total" to connectedDevices.size
                            )
                        )
                    }
                }
                else -> "UNKNOWN: $newState"
            }

            Log.d(TAG, "$state - Device: $deviceName ($deviceAddress)")

            // Update notification with number of connected devices
            if (isServerActive) {
                updateNotification(true)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

            val deviceAddress = device?.address ?: "unknown"

            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                val timestamp = System.currentTimeMillis()
                val response = "Message from  - Time: $timestamp"
                val value = response.toByteArray(Charsets.UTF_8)

                if (ActivityCompat.checkSelfPermission(
                        this@EliBleGattServerService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                    Log.d(TAG, "Characteristic read by $deviceAddress: $response")
                }
            } else {
                // Send error if characteristic doesn't exist
                if (ActivityCompat.checkSelfPermission(
                        this@EliBleGattServerService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                    Log.w(TAG, "Unknown characteristic requested by $deviceAddress")
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            val deviceAddress = device?.address ?: "unknown"

            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                val receivedValue = String(value ?: byteArrayOf())
                Log.d(TAG, "Data received from $deviceAddress: $receivedValue")

                // ✅ RESPOND IMMEDIATELY
                if (responseNeeded && device != null) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }
                mainHandler.post {
                    eventSink?.success(
                        mapOf(
                            "type" to "ble_rx",
                            "from" to deviceAddress,
                            "data" to receivedValue
                        )
                    )
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            Log.d(TAG, "Descriptor read by ${device?.address}")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.d(TAG, "MTU negotiated with ${device?.address}: $mtu")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)

            if (descriptor?.uuid == DESCRIPTOR_UUID) {
                val notificationEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val indicationEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)

                when {
                    notificationEnabled -> Log.d(TAG, "Notifications ENABLED for ${device?.address}")
                    indicationEnabled -> Log.d(TAG, "Indications ENABLED for ${device?.address}")
                    else -> Log.d(TAG, "Notifications DISABLED for ${device?.address}")
                }

                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }

                if (notificationEnabled && device != null) {
                    val currentPayload = payload
                    if (currentPayload != null) {
                        val connectedDevice = device
                        Thread {
                            Thread.sleep(100)
                            if (ActivityCompat.checkSelfPermission(
                                    this@EliBleGattServerService,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                try {
                                    @Suppress("UNCHECKED_CAST")
                                    val json = org.json.JSONObject(currentPayload as Map<String, Any>).toString()
                                    sendPayloadToDevice(connectedDevice, json)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not send payload: ${e.message}")
                                }
                            }
                        }.start()
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            super.onExecuteWrite(device, requestId, execute)
            Log.d(TAG, "Write execution: $execute for ${device?.address}")
        }

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                service?.let {
                    Log.d(TAG, "GATT service added: UUID = ${it.uuid}")
                }
            } else {
                Log.w(TAG, "Error adding GATT service: $status")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendPayloadToDevice(device: BluetoothDevice, message: String) {
        val service = bluetoothGattServer?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        val chunkSize = 20
        val bytes = message.toByteArray(Charsets.UTF_8)
        var offset = 0

        Log.d(TAG, "Sending payload to ${device.address} (${bytes.size} bytes)")

        while (offset < bytes.size) {
            val size = minOf(chunkSize, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + size)
            characteristic.value = chunk
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
            val sentOffset = offset + size
            mainHandler.post {
                eventSink?.success(
                    mapOf(
                        "type" to "ble_tx",
                        "chunk_size" to size,
                        "offset" to sentOffset,
                        "total" to bytes.size
                    )
                )
            }
            offset += size
            Thread.sleep(30)
        }
    }

    // Public methods to get server information
    fun getServerStatus(): Boolean = isServerActive
    fun getConnectedDevices(): List<String> = connectedDevices.toList()
    fun getDeviceName(): String = DEVICE_NAME
    fun getServiceUuid(): String = SERVICE_UUID.toString()
    fun getCharacteristicUuid(): String = CHARACTERISTIC_UUID.toString()
    fun getDescriptorUuid(): String = DESCRIPTOR_UUID.toString()
    fun isAdvertising(): Boolean = isAdvertisingActive

    // Function to get connection data in JSON format for QR
    fun getConnectionDataJson(): String {
        return try {
            JSONObject().apply {
                put("type", "ble_gatt_server")
                put("version", "1.0")
                put("timestamp", System.currentTimeMillis())
                put("device_name", "")
                put("service_uuid", SERVICE_UUID.toString())
                put("characteristic_uuid", CHARACTERISTIC_UUID.toString())
                put("descriptor_uuid", DESCRIPTOR_UUID.toString())
                put("status", if (isServerActive) "active" else "inactive")
                put("connected_devices", connectedDevices.size)
                put("app_name", "BLE GATT Server")
                put("protocol", "BLE_GATT")
            }.toString()
        } catch (e: Exception) {
            "BLE Connection:  - Service: $SERVICE_UUID"
        }
    }

    private fun emitServerInfo() {
        mainHandler.post {
            eventSink?.success(
                mapOf(
                    "type" to "server_info",
                    "device_name" to bluetoothAdapter?.name,
                    "service_uuid" to SERVICE_UUID.toString(),
                    "characteristic_uuid" to CHARACTERISTIC_UUID.toString(),
                    "advertising" to isAdvertisingActive,
                    "server_active" to isServerActive,
                    "connected_devices" to connectedDevices.size
                )
            )
        }
    }

    // Function to get connection data in readable format
    fun getConnectionDataFormatted(): String {
        return """
            ACTIVE BLE SERVER
            
            Device: 
            
            UUIDs:
            • Service: $SERVICE_UUID
            • Characteristic: $CHARACTERISTIC_UUID
            • Descriptor: $DESCRIPTOR_UUID
            
            Status:
            • Server: ${if (isServerActive) "ACTIVE" else "INACTIVE"}
            • Advertising: ${if (isAdvertisingActive) "ACTIVE" else "INACTIVE"}
            • Connected devices: ${connectedDevices.size}
            
            Scan this QR code to connect
            ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}
        """.trimIndent()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onDestroy() {
        Log.i(TAG, "Destroying service...")

        // Restore original device name
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                originalDeviceName?.let {
                    bluetoothAdapter?.setName(it)
                    Log.i(TAG, "Name restored to: $it")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore original name: ${e.message}")
        }

        // Stop advertising
        stopAdvertising()

        // Close GATT server
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGattServer?.close()
            Log.i(TAG, "GATT server closed")
        }

        // Clear lists
        connectedDevices.clear()

        // Notify that server stopped
        updateServerState(false)
        mainHandler.post {
            eventSink?.success(
                mapOf(
                    "type" to "service_destroyed"
                )
            )
        }
        isRunning = false

        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ])
    fun shutdownCompletely() {
        Log.i(TAG, "Complete BLE server shutdown")

        // 1️⃣ Stop advertising
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        isAdvertisingActive = false

        // 2️⃣ Disconnect devices
        connectedGattDevices.forEach {
            try {
                bluetoothGattServer?.cancelConnection(it)
            } catch (_: Exception) {}
        }
        connectedGattDevices.clear()
        connectedDevices.clear()

        // 3️⃣ Close GATT server
        try {
            bluetoothGattServer?.close()
        } catch (_: Exception) {}

        bluetoothGattServer = null

        // 4️⃣ Notify state
        updateServerState(false)

        Log.i(TAG, "BLE server completely stopped")
    }

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ])
    fun restartClean() {
        shutdownCompletely()
        initializeBluetooth()
    }

    // Method to restart the server
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun restartServer() {
        Log.i(TAG, "Restarting server...")
        stopAdvertising()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGattServer?.close()
            bluetoothGattServer = null
        }

        // Clear connections
        connectedDevices.clear()
        onConnectionChanged?.invoke(emptyList())

        // Restart
        initializeBluetooth()
    }

    fun setOnBleMessageReceived(callback: (String) -> Unit) {
        onBleMessageReceived = callback
    }
}

// Helper class to generate QR data
object BluetoothQRGenerator {
    data class ConnectionInfo(
        val deviceName: String,
        val serviceUUID: String,
        val characteristicUUID: String,
        val descriptorUUID: String,
        val serverActive: Boolean,
        val connectedDevices: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun generateQRContent(info: ConnectionInfo): String {
        return JSONObject().apply {
            put("type", "ble_gatt_connection")
            put("version", "2.0")
            put("device_name", info.deviceName)
            put("service_uuid", info.serviceUUID)
            put("characteristic_uuid", info.characteristicUUID)
            put("descriptor_uuid", info.descriptorUUID)
            put("server_status", if (info.serverActive) "active" else "inactive")
            put("connected_count", info.connectedDevices)
            put("timestamp", info.timestamp)
            put("action", "connect_to_ble_server")
        }.toString()
    }

    fun generateHumanReadableContent(info: ConnectionInfo): String {
        return """
            CONNECT TO BLE SERVER
            
            Device: ${info.deviceName}
            
            UUIDs:
            - Service: ${info.serviceUUID}
            - Characteristic: ${info.characteristicUUID}
            
            Status: ${if (info.serverActive) "ACTIVE" else "INACTIVE"}
            
            Scan with nRF Connect or similar BLE app
            ${SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date(info.timestamp))}
        """.trimIndent()
    }

    fun hasBluetoothAdvertisePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}