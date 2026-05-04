import 'dart:async';

import 'eli_ble_gatt_server_events.dart';
import 'eli_ble_gatt_server_platform_interface.dart';

export 'eli_ble_gatt_server_events.dart';

/// Main entry point for the eli_ble_gatt_server plugin.
///
/// Turns an Android device into a BLE GATT Server peripheral.
/// All methods are static; there is no need to instantiate this class.
class EliBleGattServer {
  const EliBleGattServer._();

  /// Typed stream of real-time BLE events.
  ///
  /// Listen to this stream to react to connections, received data (RX),
  /// sent data chunks (TX), and server lifecycle events.
  static Stream<BleEvent> get events =>
      EliBleGattServerPlatform.instance.events;

  /// Configure the GATT server without starting it.
  ///
  /// Call [start] afterwards to begin advertising.
  /// Use [configureAndStart] to do both in one call.
  ///
  /// [serviceUuid] — Service UUID to advertise (e.g. `"0000FFF0-0000-1000-8000-00805F9B34FB"`).
  /// [characteristicUuid] — Characteristic UUID with READ/WRITE/NOTIFY.
  /// [deviceName] — Bluetooth device name visible to scanners. Defaults to `"BLE-Server-Flutter"`.
  /// [payload] — JSON-serializable map sent automatically as the first notification
  ///             to each client that enables notifications on the characteristic.
  static Future<void> configure({
    required String serviceUuid,
    required String characteristicUuid,
    String? deviceName,
    Map<String, dynamic>? payload,
  }) =>
      EliBleGattServerPlatform.instance.configure(
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        deviceName: deviceName,
        payload: payload,
      );

  /// Configure and start the GATT server in a single call.
  ///
  /// Equivalent to calling [configure] followed by [start].
  static Future<void> configureAndStart({
    required String serviceUuid,
    required String characteristicUuid,
    String? deviceName,
    Map<String, dynamic>? payload,
  }) async {
    await configure(
      serviceUuid: serviceUuid,
      characteristicUuid: characteristicUuid,
      deviceName: deviceName,
      payload: payload,
    );
    await start();
  }

  /// Start the BLE GATT Server foreground service and begin advertising.
  ///
  /// Requires [configure] to have been called first, or use [configureAndStart].
  static Future<void> start() => EliBleGattServerPlatform.instance.start();

  /// Stop the BLE GATT Server and cancel advertising.
  static Future<void> stop() => EliBleGattServerPlatform.instance.stop();

  /// Query the current server state.
  ///
  /// Returns a map with the following keys:
  /// - `isActive` (`bool`) — whether the foreground service is running.
  /// - `advertising` (`bool`) — whether BLE advertising is active.
  /// - `connectedDevices` (`int`) — number of currently connected BLE clients.
  /// - `deviceName` (`String?`) — current Bluetooth device name.
  /// - `serviceUuid` (`String?`) — configured service UUID.
  /// - `characteristicUuid` (`String?`) — configured characteristic UUID.
  static Future<Map<String, dynamic>> getServerStatus() =>
      EliBleGattServerPlatform.instance.getServerStatus();
}
