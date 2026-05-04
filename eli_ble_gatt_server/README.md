# eli_ble_gatt_server

A Flutter plugin that turns an **Android device into a BLE GATT Server** (peripheral). It handles advertising, exposes a custom service with a READ/WRITE/NOTIFY characteristic, receives data from connected clients, and streams all events to Flutter in real time.

## Platform support

| Platform | Supported |
|----------|-----------|
| Android  | ✅ API 21+ (BLE Peripheral Mode required) |
| iOS      | ❌ Not yet |
| Web      | ❌ No |

## Features

- Start a BLE GATT Server as an Android **foreground service**
- Advertise a **custom Service UUID** and device name
- Expose a **Characteristic UUID** with READ / WRITE / NOTIFY
- **Receive data** written by BLE clients (RX events)
- **Send notifications** to all connected clients (TX via payload)
- Automatically send a **welcome payload** to each client that enables notifications
- Query server state at any time with `getServerStatus()`
- Typed event stream (`Stream<BleEvent>`) — no raw maps

## Installation

```yaml
dependencies:
  eli_ble_gatt_server: ^0.1.0
```

```
flutter pub get
```

## Android setup

### 1. Add permissions to `android/app/src/main/AndroidManifest.xml`

```xml
<!-- Inside <manifest> -->

<!-- Required for all Android versions -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<!-- Required for Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- Legacy location permission (required below Android 12) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### 2. Request runtime permissions

Use [`permission_handler`](https://pub.dev/packages/permission_handler) or any other approach:

```dart
import 'package:permission_handler/permission_handler.dart';

Future<void> requestBlePermissions() async {
  await [
    Permission.bluetoothConnect,
    Permission.bluetoothAdvertise,
  ].request();
}
```

## Quick start

```dart
import 'package:eli_ble_gatt_server/eli_ble_gatt_server.dart';

const serviceUuid      = '0000FFF0-0000-1000-8000-00805F9B34FB';
const characteristicUuid = '0000FFF1-0000-1000-8000-00805F9B34FB';

// Listen to events BEFORE starting the server
EliBleGattServer.events.listen((event) {
  switch (event) {
    case BleAdvertisingEvent e:
      print('Advertising: ${e.status}');

    case BleDeviceConnectedEvent e:
      print('Connected: ${e.address} (total: ${e.total})');

    case BleDeviceDisconnectedEvent e:
      print('Disconnected: ${e.address} (remaining: ${e.total})');

    case BleRxEvent e:
      print('Received from ${e.from}: ${e.data}');

    case BleTxEvent e:
      print('Sent chunk ${e.offset}/${e.total}');

    case BleServerInfoEvent e:
      print('Server info — active: ${e.serverActive}');

    case BleServiceDestroyedEvent _:
      print('Server stopped');

    default:
      break;
  }
});

// Configure and start in one call
await EliBleGattServer.configureAndStart(
  serviceUuid: serviceUuid,
  characteristicUuid: characteristicUuid,
  deviceName: 'MyBleDevice',
  // payload is sent automatically to each client that enables notifications
  payload: {'hello': 'world', 'version': 1},
);

// Later, stop the server
await EliBleGattServer.stop();
```

## API reference

### `EliBleGattServer` — static methods

| Method | Description |
|--------|-------------|
| `configure({...})` | Configure the server without starting it. |
| `start()` | Start the foreground service and begin advertising. |
| `configureAndStart({...})` | Configure + start in one call. |
| `stop()` | Stop the server and cancel advertising. |
| `getServerStatus()` | Returns a `Map<String, dynamic>` with current state. |
| `events` | `Stream<BleEvent>` — real-time typed event stream. |

#### `configure` / `configureAndStart` parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `serviceUuid` | `String` | ✅ | Service UUID (full 128-bit format). |
| `characteristicUuid` | `String` | ✅ | Characteristic UUID. |
| `deviceName` | `String?` | — | Bluetooth name shown to scanners. Default: `"BLE-Server-Flutter"`. |
| `payload` | `Map<String, dynamic>?` | — | JSON-serializable map sent as the first notification to each client that enables notifications. |

#### `getServerStatus()` response

```dart
{
  'isActive': bool,           // foreground service running
  'advertising': bool,        // BLE advertising active
  'connectedDevices': int,    // number of connected clients
  'deviceName': String?,      // current device name
  'serviceUuid': String?,     // configured service UUID
  'characteristicUuid': String?, // configured characteristic UUID
}
```

### Event types (`BleEvent`)

All events extend `BleEvent` with a `type` string field. Use pattern matching or `switch` on runtime type.

| Class | `type` | Fields |
|-------|--------|--------|
| `BleAdvertisingEvent` | `advertising` | `status: String` (`"started"` \| `"error"`) |
| `BleDeviceConnectedEvent` | `device_connected` | `address`, `name`, `total` |
| `BleDeviceDisconnectedEvent` | `device_disconnected` | `address`, `total` |
| `BleRxEvent` | `ble_rx` | `from: String`, `data: String` |
| `BleTxEvent` | `ble_tx` | `chunkSize`, `offset`, `total` |
| `BleServerInfoEvent` | `server_info` | `deviceName`, `serviceUuid`, `characteristicUuid`, `advertising`, `serverActive`, `connectedDevices` |
| `BleServiceDestroyedEvent` | `service_destroyed` | — |
| `BleUnknownEvent` | `unknown` | `raw: dynamic` |

## How notifications (TX) work

BLE has a maximum notification payload of 20 bytes per packet. When the server needs to send more data (e.g. a large `payload`), it automatically splits it into 20-byte chunks with a 30 ms delay between each chunk. Your client must reassemble the chunks. Each chunk triggers a `BleTxEvent` on the stream.

## Limitations

- **Android only.** iOS support is not available yet.
- **BLE Peripheral Mode** must be supported by the device hardware (most modern Android phones support it; some tablets and emulators do not).
- **TX chunk size** is fixed at 20 bytes (standard BLE MTU before negotiation).
- The plugin does not expose a method to send arbitrary notifications after startup. Notifications are triggered by the `payload` configured at startup.

## Example app

See the [`example/`](example/) folder for a complete working demo.

## License

MIT
