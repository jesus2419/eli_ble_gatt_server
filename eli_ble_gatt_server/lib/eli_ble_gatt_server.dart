import 'dart:async';
import 'package:flutter/services.dart';

class EliBleGattServer {
  static const MethodChannel _channel =
      MethodChannel('eli_ble_gatt_server');

  /// Solo para probar conexión
  static Future<String?> getPlatformVersion() async {
    return await _channel.invokeMethod<String>('getPlatformVersion');
  }

  /// CONFIGURA el servidor (OBLIGATORIO antes de iniciar)
  static Future<void> configure({
    required String serviceUuid,
    required String characteristicUuid,
    String? deviceName,
    Map<String, dynamic>? payload,
  }) async {
    await _channel.invokeMethod('configureServer', {
      'serviceUuid': serviceUuid,
      'characteristicUuid': characteristicUuid,
      'deviceName': deviceName,
      'payload': payload,
    });
  }

  /// Arranca el GATT Server
  static Future<void> start() async {
    await _channel.invokeMethod('startServer');
  }

  /// Detiene el GATT Server
  static Future<void> stop() async {
    await _channel.invokeMethod('stopServer');
  }
}
