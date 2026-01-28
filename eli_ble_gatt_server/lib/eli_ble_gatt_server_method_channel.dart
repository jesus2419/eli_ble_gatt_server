import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'eli_ble_gatt_server_platform_interface.dart';

/// An implementation of [EliBleGattServerPlatform] that uses method channels.
class MethodChannelEliBleGattServer extends EliBleGattServerPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('eli_ble_gatt_server');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
