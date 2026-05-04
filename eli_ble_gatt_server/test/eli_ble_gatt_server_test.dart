import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_platform_interface.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockEliBleGattServerPlatform
    with MockPlatformInterfaceMixin
    implements EliBleGattServerPlatform {
  @override
  Stream<BleEvent> get events => const Stream.empty();

  @override
  Future<void> configure({
    required String serviceUuid,
    required String characteristicUuid,
    String? deviceName,
    Map<String, dynamic>? payload,
  }) async {}

  @override
  Future<void> start() async {}

  @override
  Future<void> stop() async {}

  @override
  Future<Map<String, dynamic>> getServerStatus() async => {
        'isActive': true,
        'advertising': true,
        'connectedDevices': 2,
        'deviceName': 'TestDevice',
        'serviceUuid': '0000FFF0-0000-1000-8000-00805F9B34FB',
        'characteristicUuid': '0000FFF1-0000-1000-8000-00805F9B34FB',
      };

  @override
  Future<void> sendMessage(String message) async {}
}

void main() {
  final EliBleGattServerPlatform initialPlatform =
      EliBleGattServerPlatform.instance;

  test('$MethodChannelEliBleGattServer is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelEliBleGattServer>());
  });

  test('getServerStatus returns expected map', () async {
    final mock = MockEliBleGattServerPlatform();
    EliBleGattServerPlatform.instance = mock;

    final status = await EliBleGattServer.getServerStatus();

    expect(status['isActive'], true);
    expect(status['connectedDevices'], 2);
    expect(status['deviceName'], 'TestDevice');

    // Restore original instance
    EliBleGattServerPlatform.instance = initialPlatform;
  });

  test('configure delegates to platform', () async {
    final mock = MockEliBleGattServerPlatform();
    EliBleGattServerPlatform.instance = mock;

    await expectLater(
      EliBleGattServer.configure(
        serviceUuid: '0000FFF0-0000-1000-8000-00805F9B34FB',
        characteristicUuid: '0000FFF1-0000-1000-8000-00805F9B34FB',
      ),
      completes,
    );

    EliBleGattServerPlatform.instance = initialPlatform;
  });
}
