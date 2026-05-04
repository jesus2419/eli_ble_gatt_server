package com.example.eli_ble_gatt_server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel

/** EliBleGattServerPlugin */
class EliBleGattServerPlugin :
    FlutterPlugin,
    MethodCallHandler {

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        // MethodChannel (acciones puntuales)
        methodChannel = MethodChannel(
            binding.binaryMessenger,
            "eli_ble_gatt_server"
        )
        methodChannel.setMethodCallHandler(this)

        // EventChannel (eventos BLE en tiempo real)
        eventChannel = EventChannel(
            binding.binaryMessenger,
            "eli_ble_gatt_server/events"
        )

        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                // Conectamos el Service con Flutter
                EliBleGattServerService.eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                EliBleGattServerService.eventSink = null
            }
        })
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {

            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }

            "configureServer" -> {
                val serviceUuid = call.argument<String>("serviceUuid")!!
                val characteristicUuid = call.argument<String>("characteristicUuid")!!
                val deviceName = call.argument<String>("deviceName")
                val payload = call.argument<Map<String, Any>>("payload")

                EliBleGattServerService.configure(
                    serviceUuid,
                    characteristicUuid,
                    deviceName,
                    payload
                )

                result.success(true)
            }

            "startServer" -> {
                val intent = Intent(context, EliBleGattServerService::class.java)
                context.startForegroundService(intent)
                result.success(true)
            }

            "stopServer" -> {
                val intent = Intent(context, EliBleGattServerService::class.java)
                context.stopService(intent)
                result.success(true)
            }

            "getServerStatus" -> {
                result.success(EliBleGattServerService.getStatusMap())
            }

            "sendMessage" -> {
                val message = call.argument<String>("message") ?: ""
                val service = EliBleGattServerService.instance
                if (service == null) {
                    result.error("SERVICE_NOT_RUNNING", "BLE server is not running", null)
                    return
                }
                Thread {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        service.sendMessageToConnectedDevices(message)
                    }
                }.start()
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        EliBleGattServerService.eventSink = null
    }
}
