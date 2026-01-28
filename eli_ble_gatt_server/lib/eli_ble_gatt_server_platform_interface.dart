import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'eli_ble_gatt_server_method_channel.dart';

abstract class EliBleGattServerPlatform extends PlatformInterface {
  /// Constructs a EliBleGattServerPlatform.
  EliBleGattServerPlatform() : super(token: _token);

  static final Object _token = Object();

  static EliBleGattServerPlatform _instance = MethodChannelEliBleGattServer();

  /// The default instance of [EliBleGattServerPlatform] to use.
  ///
  /// Defaults to [MethodChannelEliBleGattServer].
  static EliBleGattServerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [EliBleGattServerPlatform] when
  /// they register themselves.
  static set instance(EliBleGattServerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
