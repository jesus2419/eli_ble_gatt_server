import 'package:flutter_test/flutter_test.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_platform_interface.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockEliBleGattServerPlatform
    with MockPlatformInterfaceMixin
    implements EliBleGattServerPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final EliBleGattServerPlatform initialPlatform = EliBleGattServerPlatform.instance;

  test('$MethodChannelEliBleGattServer is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelEliBleGattServer>());
  });

  test('getPlatformVersion', () async {
    EliBleGattServer eliBleGattServerPlugin = EliBleGattServer();
    MockEliBleGattServerPlatform fakePlatform = MockEliBleGattServerPlatform();
    EliBleGattServerPlatform.instance = fakePlatform;

    expect(await eliBleGattServerPlugin.getPlatformVersion(), '42');
  });
}
