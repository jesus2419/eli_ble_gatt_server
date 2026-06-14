# eli_ble_gatt_server

Flutter plugin that turns a device into a **BLE GATT Server** (peripheral) on **Android and iOS**. It handles advertising, exposes a custom service with a READ/WRITE/NOTIFY characteristic, receives data from connected clients, and streams all events to Flutter in real time.

## Platform support

| Platform | Supported |
|----------|-----------|
| Android  | ✅ API 21+ (BLE Peripheral Mode required) |
| iOS      | ✅ iOS 13+ (foreground only) |

## Features

- Advertise a **custom Service UUID** and device name
- Expose a **Characteristic UUID** with READ / WRITE / NOTIFY
- **Receive data** written by BLE clients (`BleRxEvent`)
- **Send notifications** to all connected clients at any time with `sendMessage()`
- Automatically send a **welcome payload** (JSON) to each client that enables notifications
- Query server state at any time with `getServerStatus()`
- Typed event stream (`Stream<BleEvent>`) — no raw maps

## Repository layout

The publishable Flutter plugin lives in the [`eli_ble_gatt_server/`](eli_ble_gatt_server/) subfolder:

| Path | Description |
|------|-------------|
| [`eli_ble_gatt_server/`](eli_ble_gatt_server/) | The plugin package (Dart API + Android & iOS native code) |
| [`eli_ble_gatt_server/README.md`](eli_ble_gatt_server/README.md) | Full documentation, setup and API reference (also shown on pub.dev) |
| [`eli_ble_gatt_server/example/`](eli_ble_gatt_server/example/) | Runnable demo app |

## Quick start

```dart
import 'package:eli_ble_gatt_server/eli_ble_gatt_server.dart';

await EliBleGattServer.configureAndStart(
  serviceUuid: '0000FFF0-0000-1000-8000-00805F9B34FB',
  characteristicUuid: '0000FFF1-0000-1000-8000-00805F9B34FB',
  deviceName: 'MyBleDevice',
  payload: {'hello': 'world', 'version': 1},
);

EliBleGattServer.events.listen((event) {
  // BleAdvertisingEvent, BleDeviceConnectedEvent, BleRxEvent, BleTxEvent, ...
});
```

See the [package README](eli_ble_gatt_server/README.md) for **Android setup**, **iOS setup**, the full API reference, and platform-specific notes.

## Platform notes

- **Android** runs the server as a foreground service and can change the advertised Bluetooth device name.
- **iOS** runs foreground-only and has platform constraints: the device name is used only as the advertised local name, centrals are identified by a UUID (no MAC / name), and "connection" maps to a notification subscription. Requires a **real device** (the Simulator has no BLE peripheral support). Details in the [package README](eli_ble_gatt_server/README.md#ios-setup).

## License

MIT — see [LICENSE](LICENSE).
