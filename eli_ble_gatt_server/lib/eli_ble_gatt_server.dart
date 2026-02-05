import 'dart:async';
import 'package:flutter/foundation.dart'; // debugPrint
import 'package:flutter/services.dart';
import 'eli_ble_gatt_server_events.dart';


class EliBleGattServer {
  static const MethodChannel _channel =
      MethodChannel('eli_ble_gatt_server');

  static const EventChannel _eventChannel =
      EventChannel('eli_ble_gatt_server/events');

  static Stream<BleEvent>? _eventStream;

  /// Stream tipado
  static Stream<BleEvent> get events {
    _eventStream ??= _eventChannel.receiveBroadcastStream().map((raw) {
      final event = BleEvent.from(raw);

      debugPrint(
        '[EliBleGattServer][EVENT] '
        '${DateTime.now().toIso8601String()} → ${event.type}',
      );

      return event;
    });

    return _eventStream!;
  }

  static Future<String?> getPlatformVersion() async {
    return await _channel.invokeMethod<String>('getPlatformVersion');
  }

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

    static Future<void> configure_and_start({
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

    await start();
  }

  static Future<void> start() async {
    await _channel.invokeMethod('startServer');
  }

  static Future<void> stop() async {
    await _channel.invokeMethod('stopServer');
  }
}
