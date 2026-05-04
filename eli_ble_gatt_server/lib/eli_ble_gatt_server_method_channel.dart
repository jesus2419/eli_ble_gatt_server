import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'eli_ble_gatt_server_events.dart';
import 'eli_ble_gatt_server_platform_interface.dart';

class MethodChannelEliBleGattServer extends EliBleGattServerPlatform {
  @visibleForTesting
  final methodChannel = const MethodChannel('eli_ble_gatt_server');

  static const EventChannel _eventChannel =
      EventChannel('eli_ble_gatt_server/events');

  Stream<BleEvent>? _eventStream;

  @override
  Stream<BleEvent> get events {
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

  @override
  Future<void> configure({
    required String serviceUuid,
    required String characteristicUuid,
    String? deviceName,
    Map<String, dynamic>? payload,
  }) async {
    await methodChannel.invokeMethod<void>('configureServer', {
      'serviceUuid': serviceUuid,
      'characteristicUuid': characteristicUuid,
      'deviceName': deviceName,
      'payload': payload,
    });
  }

  @override
  Future<void> start() async {
    await methodChannel.invokeMethod<void>('startServer');
  }

  @override
  Future<void> stop() async {
    await methodChannel.invokeMethod<void>('stopServer');
  }

  @override
  Future<Map<String, dynamic>> getServerStatus() async {
    final result = await methodChannel
        .invokeMapMethod<String, dynamic>('getServerStatus');
    return result ?? {};
  }
}
