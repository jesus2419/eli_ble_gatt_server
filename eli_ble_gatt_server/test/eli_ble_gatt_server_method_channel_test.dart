import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platform = MethodChannelEliBleGattServer();
  const channel = MethodChannel('eli_ble_gatt_server');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      switch (methodCall.method) {
        case 'getServerStatus':
          return {
            'isActive': false,
            'advertising': false,
            'connectedDevices': 0,
            'deviceName': 'TestDevice',
            'serviceUuid': '0000FFF0-0000-1000-8000-00805F9B34FB',
            'characteristicUuid': '0000FFF1-0000-1000-8000-00805F9B34FB',
          };
        case 'configureServer':
        case 'startServer':
        case 'stopServer':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('getServerStatus returns map from platform', () async {
    final status = await platform.getServerStatus();
    expect(status['isActive'], false);
    expect(status['connectedDevices'], 0);
    expect(status['deviceName'], 'TestDevice');
  });

  test('configure completes without error', () async {
    await expectLater(
      platform.configure(
        serviceUuid: '0000FFF0-0000-1000-8000-00805F9B34FB',
        characteristicUuid: '0000FFF1-0000-1000-8000-00805F9B34FB',
        deviceName: 'Test',
        payload: {'key': 'value'},
      ),
      completes,
    );
  });

  test('start completes without error', () async {
    await expectLater(platform.start(), completes);
  });

  test('stop completes without error', () async {
    await expectLater(platform.stop(), completes);
  });
}
