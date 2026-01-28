package com.example.eli_ble_gatt_server

import android.content.Context
import android.content.Intent
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** EliBleGattServerPlugin */
class EliBleGattServerPlugin :
    FlutterPlugin,
    MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        channel = MethodChannel(
            binding.binaryMessenger,
            "eli_ble_gatt_server"
        )
        channel.setMethodCallHandler(this)
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

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
