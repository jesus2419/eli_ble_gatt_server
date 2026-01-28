
import 'eli_ble_gatt_server_platform_interface.dart';

class EliBleGattServer {
  Future<String?> getPlatformVersion() {
    return EliBleGattServerPlatform.instance.getPlatformVersion();
  }
}
