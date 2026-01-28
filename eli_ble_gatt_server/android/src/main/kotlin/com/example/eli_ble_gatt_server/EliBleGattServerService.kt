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

        // UUIDs para el servicio y características

        // uiid de 16 bits
        
        val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Nombre del dispositivo
        //const val DEVICE_NAME = "DispositivoBLEUrbanito"

        lateinit var SERVICE_UUID: UUID
        lateinit var CHARACTERISTIC_UUID: UUID
        lateinit var DEVICE_NAME: String

        var payload: Map<*, *>? = null

        var isRunning = false


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



        // Constantes para tipos de servicio (valores numéricos)
        private const val SERVICE_TYPE_PRIMARY = 0

        var eventSink: EventChannel.EventSink? = null
        private val mainHandler = Handler(Looper.getMainLooper())


        /*
        *
        eventSink?.success(
    mapOf(
        "type" to "device_connected",
        "address" to device.address
    )
)
        * */

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

    // Variables de estado
    private var isAdvertisingActive = false
    private var isServerActive = false
    private var connectedDevices = mutableListOf<String>()

    // Callback para notificar cambios de estado
    private var onServerStateChanged: ((Boolean) -> Unit)? = null
    private var onConnectionChanged: ((List<String>) -> Unit)? = null

    private val connectedGattDevices = mutableSetOf<BluetoothDevice>()

    private var onBleMessageReceived: ((String) -> Unit)? = null




    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 Servicio Bluetooth GATT creado")

        createNotificationChannel()
        startForegroundServiceWithType()
        isRunning = true


        // Guardar el nombre original del dispositivo
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            originalDeviceName = bluetoothAdapter?.name
            Log.d(TAG, "Nombre original del dispositivo guardado: $originalDeviceName")
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
                description = "Canal para el servicio Bluetooth GATT"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Canal de notificación creado")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendPaidTarifa(tarifaRaw: String) {

        Log.d(TAG, "💰 Tarifa RAW recibida: $tarifaRaw")

        // 🔹 Limpiar prefijo si viene incluido
        val tarifa = tarifaRaw
            .replace("TARIFA:", "")
            .trim()

        // 🔹 JSON válido SIEMPRE
        val jsonPaid = """{"paid":"$tarifa"}\n"""

        Log.d(TAG, "📤 JSON generado: $jsonPaid")

        sendMessageToConnectedDevices(jsonPaid)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessageToConnectedDevices(message: String) {

        if (connectedGattDevices.isEmpty()) {
            Log.w(TAG, "⚠ No hay dispositivos conectados")
            return
        }

        val service = bluetoothGattServer?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        // 🔒 BLE REAL: 20 bytes por NOTIFY
        val chunkSize = 20

        val payload = message.toByteArray(Charsets.UTF_8)

        Log.d(TAG, "📦 Enviando TEXTO plano (${payload.size} bytes) en chunks de 20")

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


            // ⏱ pequeño delay para no saturar BLE
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
                Log.d(TAG, "Servicio foreground iniciado con tipo apropiado")
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar servicio foreground: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            // Android 9 y anteriores
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔵 Servidor Bluetooth GATT Activo")
            .setContentText("Dispositivo: ")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun initializeBluetooth() {
        Log.i(TAG, "🔄 Inicializando Bluetooth...")

        try {
            bluetoothManager = getSystemService(BluetoothManager::class.java)
            bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                Log.e(TAG, "❌ Bluetooth no soportado en este dispositivo")
                updateServerState(false)
                return
            }

            if (!bluetoothAdapter!!.isEnabled) {
                Log.e(TAG, "❌ Bluetooth no está habilitado")
                updateServerState(false)
                return
            }

            bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

            if (bluetoothLeAdvertiser == null) {
                Log.e(TAG, "❌ Bluetooth LE Advertising no soportado")
                updateServerState(false)
                return
            }

            setupGattServer()
            startAdvertising()

        } catch (e: SecurityException) {
            Log.e(TAG, "🔒 Error de seguridad: ${e.message}")
            updateServerState(false)
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error inicializando Bluetooth: ${e.message}")
            updateServerState(false)
        }
    }

    private fun updateServerState(isActive: Boolean) {
        val wasActive = isServerActive
        isServerActive = isActive
        isAdvertisingActive = isActive

        if (wasActive != isActive) {
            Log.i(TAG, if (isActive) "✅ Servidor ACTIVADO" else "⏸ Servidor DESACTIVADO")

            // Notificar cambio de estado
            onServerStateChanged?.invoke(isActive)

            // Actualizar notificación
            updateNotification(isActive)
        }
    }

    private fun updateNotification(isActive: Boolean) {
        val notification = if (isActive) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🔵 Servidor BLE Activo")
                .setContentText("$ - ${connectedDevices.size} dispositivo(s) conectado(s)")
                .setSmallIcon(R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚪ Servidor BLE Inactivo")
                .setContentText("Servicio detenido")
                .setSmallIcon(R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .build()
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    // Función para establecer callback de cambio de estado
    fun setServerStateCallback(callback: (Boolean) -> Unit) {
        onServerStateChanged = callback
        // Notificar estado actual inmediatamente
        callback(isServerActive)
    }

    // Función para establecer callback de cambio de conexiones
    fun setConnectionCallback(callback: (List<String>) -> Unit) {
        onConnectionChanged = callback
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        Log.i(TAG, "🛠 Configurando servidor GATT...")






        try {
            bluetoothGattServer = bluetoothManager?.openGattServer(this, gattServerCallback)

            if (bluetoothGattServer == null) {
                Log.e(TAG, "❌ No se pudo crear el servidor GATT")
                return
            }

            // Usar el nombre completo
            val service = BluetoothGattService(SERVICE_UUID, SERVICE_TYPE_PRIMARY)

            // Crear característica de lectura/escritura/notificación
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // Crear descriptor para notificaciones
            val descriptor = BluetoothGattDescriptor(
                DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
            )

            // Configurar valor inicial del descriptor para notificaciones habilitadas
            //descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.addDescriptor(descriptor)
            service.addCharacteristic(characteristic)

            // Agregar servicio al servidor
            val success = bluetoothGattServer?.addService(service)

            if (success == true) {
                Log.i(TAG, "✅ Servicio GATT configurado correctamente: $SERVICE_UUID")
            } else {
                Log.e(TAG, "❌ Error al agregar servicio GATT")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "🔒 Error de seguridad configurando GATT: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error configurando GATT: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        Log.i(TAG, "📢 Iniciando advertising...")



        bluetoothLeAdvertiser?.let { advertiser ->
            try {
                // Configurar nombre del dispositivo
                /*
                try {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // Guardar nombre original si no está guardado
                        if (originalDeviceName == null) {
                            originalDeviceName = bluetoothAdapter?.name
                        }

                        // Cambiar nombre del dispositivo
                        val setNameSuccess = bluetoothAdapter?.setName(DEVICE_NAME)
                        Log.i(TAG, if (setNameSuccess == true)
                            "✅ Nombre cambiado a: $DEVICE_NAME"
                        else "⚠ No se pudo cambiar el nombre")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠ No se pudo cambiar el nombre del dispositivo: ${e.message}")
                }
                */


                // Configuración del advertising
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0) // 0 = sin timeout
                    .build()

                // Datos del advertising
                val advertiseData = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()

                // Datos de respuesta al scan
                val scanResponse = AdvertiseData.Builder()
                    .addManufacturerData(0xFFFF, "BLE_GATT_SERVER".toByteArray())
                    .build()

                // Iniciar advertising
                advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
                Log.i(TAG, "📡 Advertising iniciado")

            } catch (e: SecurityException) {
                Log.e(TAG, "🔒 Error de seguridad en advertising: ${e.message}")
                updateServerState(false)
            } catch (e: Exception) {
                Log.e(TAG, "💥 Error en advertising: ${e.message}")
                updateServerState(false)
            }
        } ?: run {
            Log.e(TAG, "❌ BluetoothLeAdvertiser no disponible")
            updateServerState(false)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        Log.i(TAG, "🛑 Deteniendo advertising...")

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
            Log.i(TAG, "✅ Advertising detenido")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error al detener advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.i(TAG, "✅ Advertising iniciado correctamente")
            Log.i(TAG, "📱 Dispositivo visible como: ")
            Log.i(TAG, "🔧 Modo: ${settingsInEffect.mode}, Potencia: ${settingsInEffect.txPowerLevel}")
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
            Log.e(TAG, "❌ Error al iniciar advertising: $errorMessage")
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


            val deviceAddress = device?.address ?: "desconocido"
            val deviceName = device?.name ?: "sin nombre"
            val state = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Agregar dispositivo a la lista de conectados
                    if (!connectedDevices.contains(deviceAddress)) {
                        connectedDevices.add(deviceAddress)

                        connectedGattDevices.add(device)

                        onConnectionChanged?.invoke(connectedDevices.toList())
                    }


                    "✅ CONECTADO"
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
                    // Remover dispositivo de la lista
                    connectedDevices.remove(deviceAddress)

                    connectedGattDevices.remove(device)


                    onConnectionChanged?.invoke(connectedDevices.toList())
                    "❌ DESCONECTADO"
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
                else -> "❓ DESCONOCIDO: $newState"
            }

            Log.d(TAG, "🔗 $state - Dispositivo: $deviceName ($deviceAddress)")

            // Actualizar notificación con cantidad de dispositivos conectados
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

            val deviceAddress = device?.address ?: "desconocido"

            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                val timestamp = System.currentTimeMillis()
                val response = "Mensaje desde  - Tiempo: $timestamp"
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
                    Log.d(TAG, "📖 Característica leída por $deviceAddress: $response")
                }
            } else {
                // Enviar error si la característica no existe
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
                    Log.w(TAG, "⚠ Característica desconocida solicitada por $deviceAddress")
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

            val deviceAddress = device?.address ?: "desconocido"

            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                val receivedValue = String(value ?: byteArrayOf())
                Log.d(TAG, "📝 Datos recibidos de $deviceAddress: $receivedValue")

                /*sendMessageToConnectedDevices(
                    """{"status":"connected","ts":${System.currentTimeMillis()}}"""
                )

                 */

                // ✅ RESPONDER INMEDIATAMENTE
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

                if (receivedValue.startsWith("READY")) {
                    //
                    val jsonPayload =
                        """{"uId":"ANDROID PAYLOAD","ord":true,"bO":31217,"dId":"2201116PG","iP":true,"bP":2,"a":[{"bId":"RELG117","pass":1,"cD":1747944074421,"dC":"N/A","price":15},{"bId":"RELG117","pass":1,"cD":1747943565016,"dC":"N/A","price":15}]}"""

                    val payloadJson: String = JSONObject(payload).toString()

                    sendMessageToConnectedDevices(payloadJson)
                    //onBleMessageReceived?.invoke(receivedValue)

                }
                if (receivedValue.startsWith("JSON_OK")) {
                    // 🚀 AVISAR A LA UI
                    onBleMessageReceived?.invoke(receivedValue)

                    mainHandler.post {

                            eventSink?.success(
                                mapOf(
                                    "type" to "closed",
                                    "data" to receivedValue
                                )
                            )
                        }
                }

                if (receivedValue.startsWith("TARIFA:")) {

                    val tarifa = receivedValue
                        .substringAfter("TARIFA:")
                        .toDoubleOrNull()

                    if (tarifa != null) {
                        mainHandler.post {

                            eventSink?.success(
                                mapOf(
                                    "type" to "ble_tarifa",
                                    "from" to deviceAddress,
                                    "data" to receivedValue
                                )
                            )
                        }


                        Log.d(TAG, "💰 Tarifa recibida: $tarifa")
                        /*
                        val jsonPaid = """{"paid":$tarifa}\n"""

                        Log.d(TAG, "📤 JSON generado: $jsonPaid")
                        sendMessageToConnectedDevices(jsonPaid)


                         */

                    } else {
                        Log.e(TAG, "❌ Formato TARIFA inválido")
                    }
                }









                // Aquí puedes procesar los datos recibidos
                // Por ejemplo, guardarlos, analizarlos, etc.

                if (responseNeeded && ActivityCompat.checkSelfPermission(
                        this@EliBleGattServerService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
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
            Log.d(TAG, "📖 Descriptor leído por ${device?.address}")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.d(TAG, "📐 MTU negociado con ${device?.address}: $mtu")
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
                    notificationEnabled -> Log.d(TAG, "🔔 Notificaciones HABILITADAS para ${device?.address}")
                    indicationEnabled -> Log.d(TAG, "📨 Indicaciones HABILITADAS para ${device?.address}")
                    else -> Log.d(TAG, "🔕 Notificaciones DESHABILITADAS para ${device?.address}")
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

                // 🚀 SOLO DESPUÉS DE RESPONDER
                if (notificationEnabled && device != null) {

                    /*sendMessageToConnectedDevices(
                        """{"status":"connected","ts":${System.currentTimeMillis()}}"""
                    )*/


                    /*

                    val jsonPayload = """{"uId":"uGWQpjewgEVOap7Z4UOsCnVuactc","ord":true,"bO":31217,"dId":"2201116PG","iP":true,"bP":2,"a":[{"bId":"RELG117","pass":1,"cD":1747944074421,"dC":"N/A","price":15},{"bId":"RELG117","pass":1,"cD":1747943565016,"dC":"N/A","price":15}]}"""
                    sendMessageToConnectedDevices(jsonPayload)


                     */



                }

            }



        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            super.onExecuteWrite(device, requestId, execute)
            Log.d(TAG, "✍️ Ejecución de escritura: $execute para ${device?.address}")


        }

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                service?.let {
                    Log.d(TAG, "✅ Servicio GATT agregado: UUID = ${it.uuid}")
                }
            } else {
                Log.w(TAG, "⚠ Error al agregar servicio GATT: $status")
            }
        }






        /*
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun sendMessageToConnectedDevices(message: String) {

            if (connectedGattDevices.isEmpty()) {
                Log.w(TAG, "⚠ No hay dispositivos conectados")
                return
            }

            val service = bluetoothGattServer
                ?.getService(SERVICE_UUID)

            val characteristic = service
                ?.getCharacteristic(CHARACTERISTIC_UUID)

            if (characteristic == null) {
                Log.e(TAG, "❌ Característica no encontrada")
                return
            }

            characteristic.value = message.toByteArray(Charsets.UTF_8)

            connectedGattDevices.forEach { device ->
                val success = bluetoothGattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false // false = NOTIFY, true = INDICATE
                )

                Log.d(TAG, "📤 NOTIFY a ${device.address} -> $success")
            }
        }

         */


    }

    // Métodos públicos para obtener información del servidor
    fun getServerStatus(): Boolean = isServerActive

    fun getConnectedDevices(): List<String> = connectedDevices.toList()

    fun getDeviceName(): String = DEVICE_NAME

    fun getServiceUuid(): String = SERVICE_UUID.toString()

    fun getCharacteristicUuid(): String = CHARACTERISTIC_UUID.toString()

    fun getDescriptorUuid(): String = DESCRIPTOR_UUID.toString()

    fun isAdvertising(): Boolean = isAdvertisingActive

    // Función para obtener datos de conexión en formato JSON para QR
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


    // Función para obtener datos de conexión en formato legible
    fun getConnectionDataFormatted(): String {
        return """
            🟢 SERVIDOR BLE ACTIVO
            
            📱 Dispositivo: 
            
            🔗 UUIDs:
            • Servicio: $SERVICE_UUID
            • Característica: $CHARACTERISTIC_UUID
            • Descriptor: $DESCRIPTOR_UUID
            
            📊 Estado:
            • Servidor: ${if (isServerActive) "ACTIVO" else "INACTIVO"}
            • Advertising: ${if (isAdvertisingActive) "ACTIVO" else "INACTIVO"}
            • Dispositivos conectados: ${connectedDevices.size}
            
            ⚡ Escanea este código QR para conectar
            ⏰ ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}
        """.trimIndent()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onDestroy() {
        Log.i(TAG, "🛑 Destruyendo servicio...")

        // Restaurar el nombre original del dispositivo
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                originalDeviceName?.let {
                    bluetoothAdapter?.setName(it)
                    Log.i(TAG, "✅ Nombre restaurado a: $it")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠ No se pudo restaurar el nombre original: ${e.message}")
        }

        // Detener advertising
        stopAdvertising()

        // Cerrar servidor GATT
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGattServer?.close()
            Log.i(TAG, "✅ Servidor GATT cerrado")
        }

        // Limpiar listas
        connectedDevices.clear()

        // Notificar que el servidor se detuvo
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
        Log.i(TAG, "🔴 Servicio destruido")
    }


    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ])
    fun shutdownCompletely() {
        Log.i(TAG, "🧹 Shutdown COMPLETO del servidor BLE")

        // 1️⃣ Detener advertising
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        isAdvertisingActive = false

        // 2️⃣ Desconectar dispositivos
        connectedGattDevices.forEach {
            try {
                bluetoothGattServer?.cancelConnection(it)
            } catch (_: Exception) {}
        }
        connectedGattDevices.clear()
        connectedDevices.clear()

        // 3️⃣ Cerrar GATT server
        try {
            bluetoothGattServer?.close()
        } catch (_: Exception) {}

        bluetoothGattServer = null

        // 4️⃣ Notificar estado
        updateServerState(false)

        Log.i(TAG, "✅ Servidor BLE completamente detenido")
    }

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ])
    fun restartClean() {
        shutdownCompletely()
        initializeBluetooth()
    }


    // Método para reiniciar el servidor
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun restartServer() {
        Log.i(TAG, "🔄 Reiniciando servidor...")
        stopAdvertising()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGattServer?.close()
            bluetoothGattServer = null
        }

        // Limpiar conexiones
        connectedDevices.clear()
        onConnectionChanged?.invoke(emptyList())

        // Reiniciar
        initializeBluetooth()
    }

    fun setOnBleMessageReceived(callback: (String) -> Unit) {
        onBleMessageReceived = callback
    }
}

// Clase de ayuda para generar datos QR
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
            CONECTAR A SERVIDOR BLE
            
            Dispositivo: ${info.deviceName}
            
            UUIDs:
            - Servicio: ${info.serviceUUID}
            - Característica: ${info.characteristicUUID}
            
            Estado: ${if (info.serverActive) "🟢 ACTIVO" else "🔴 INACTIVO"}
            
            Escanea con nRF Connect o app BLE similar
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