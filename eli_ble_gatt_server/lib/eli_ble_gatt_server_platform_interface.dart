import 'dart:async';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'eli_ble_gatt_server_events.dart';
import 'eli_ble_gatt_server_method_channel.dart';

abstract class EliBleGattServerPlatform extends PlatformInterface {
  EliBleGattServerPlatform() : super(token: _token);

  static final Object _token = Object();

  static EliBleGattServerPlatform _instance = MethodChannelEliBleGattServer();

  static EliBleGattServerPlatform get instance => _instance;

  static set instance(EliBleGattServerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  /// Typed stream of BLE events.
  Stream<BleEvent> get events;

  /// Configure the GATT server (does not start advertising).
  ///
  /// [payload] is a JSON-serializable map that will be sent as the first
  /// notification to each client that enables notifications on the characteristic.
  Future<void> configure({
    required String serviceUuid,
    required String characteristicUuid,
    String? deviceName,
    Map<String, dynamic>? payload,
  });

  /// Start the foreground service and begin BLE advertising.
  Future<void> start();

  /// Stop the foreground service and BLE advertising.
  Future<void> stop();

  /// Query the current server state.
  ///
  /// Returns a map with keys:
  /// `isActive`, `advertising`, `connectedDevices`, `deviceName`,
  /// `serviceUuid`, `characteristicUuid`.
  Future<Map<String, dynamic>> getServerStatus();
}
