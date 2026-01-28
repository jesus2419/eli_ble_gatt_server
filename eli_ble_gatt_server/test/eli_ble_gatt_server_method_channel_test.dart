import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  MethodChannelEliBleGattServer platform = MethodChannelEliBleGattServer();
  const MethodChannel channel = MethodChannel('eli_ble_gatt_server');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        return '42';
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion', () async {
    expect(await platform.getPlatformVersion(), '42');
  });
}
