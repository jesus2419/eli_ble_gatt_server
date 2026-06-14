# eli_ble_gatt_server

A Flutter plugin that turns a device into a **BLE GATT Server** (peripheral). It handles advertising, exposes a custom service with a READ/WRITE/NOTIFY characteristic, receives data from connected clients, and streams all events to Flutter in real time.

## Platform support

| Platform | Supported |
|----------|-----------|
| Android  | ✅ API 21+ (BLE Peripheral Mode required) |
| iOS      | ✅ iOS 13+ (foreground only) |

## Features

- Start a BLE GATT Server as an Android **foreground service**
- Advertise a **custom Service UUID** and device name
- Expose a **Characteristic UUID** with READ / WRITE / NOTIFY
- **Receive data** written by BLE clients (`BleRxEvent`)
- **Send notifications** to all connected clients at any time with `sendMessage()`
- Automatically send a **welcome payload** (JSON) to each client that enables notifications
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

## iOS setup

### 1. Add the Bluetooth usage description to `ios/Runner/Info.plist`

This key is **required** — without it the app crashes the moment CoreBluetooth starts on iOS 13+.

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to run a BLE GATT server and exchange data with nearby devices.</string>
```

### 2. Test on a real device

CoreBluetooth's **peripheral role does not work on the iOS Simulator** — you must run on a physical iPhone/iPad.

### iOS limitations & differences vs Android

iOS exposes BLE peripheral functionality differently from Android. The Dart API and event contract are identical, but on iOS:

- **No device-name change.** iOS does not allow changing the Bluetooth name; `deviceName` is used only as the advertised local name (`CBAdvertisementDataLocalNameKey`).
- **No MAC / central name.** iOS only exposes the central's `identifier` (a UUID). In `device_connected`/`device_disconnected`/`ble_rx`, `address`/`from` is that UUID and `name` is always `"unknown"`.
- **No manufacturer data** in the peripheral advertisement (Android's scan-response manufacturer data has no iOS equivalent).
- **"Connection" = notification subscription.** A `device_connected` event is emitted when a central subscribes to notifications (which is also when the welcome `payload` is delivered); `device_disconnected` when it unsubscribes.
- **Foreground only.** There is no foreground-service/persistent-notification concept; `isActive` reflects that the peripheral is powered on and advertising. The server stops advertising when the app is suspended.

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
  // payload is sent automatically as the first notification to each client
  // that enables notifications on the characteristic (CCCD write)
  payload: {'hello': 'world', 'version': 1},
);

// Send a message to all connected clients at any time
await EliBleGattServer.sendMessage('ping');

// Query server state without relying on the event stream
final status = await EliBleGattServer.getServerStatus();
print('Connected devices: ${status['connectedDevices']}');

// Stop the server
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
| `sendMessage(String)` | Send a text notification to all connected clients. |
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
  'isActive': bool,           // server running (Android: foreground service; iOS: peripheral powered on & advertising)
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

BLE has a maximum notification payload of 20 bytes per packet. Both `sendMessage()` and the welcome `payload` split data into 20-byte chunks. On Android a 30 ms delay is inserted between chunks; on iOS chunks are paced by CoreBluetooth's transmit-queue backpressure (`peripheralManagerIsReadyToUpdateSubscribers`). Each chunk triggers a `BleTxEvent` on the stream. Your client is responsible for reassembling chunks into the full message.

```
Server                          Client
  │── chunk 1 (20 B) ──────────▶│
  │   BleTxEvent(offset=20)      │
  │── chunk 2 (20 B) ──────────▶│
  │   BleTxEvent(offset=40)      │
  │── chunk 3 (N B)  ──────────▶│
  │   BleTxEvent(offset=total)   │
```

## Receiving data from clients (RX)

When a BLE client writes to the characteristic, the plugin emits a `BleRxEvent`:

```dart
EliBleGattServer.events.listen((event) {
  if (event is BleRxEvent) {
    print('Received from ${event.from}: ${event.data}');
  }
});
```

## Limitations

- **BLE Peripheral Mode** must be supported by the device hardware (most modern Android phones support it; some tablets and emulators do not). On iOS this requires a real device (the Simulator has no BLE peripheral support).
- **iOS runs foreground only** and has platform-specific differences — see [iOS setup](#ios-setup).
- **TX chunk size** is fixed at 20 bytes (standard BLE MTU before negotiation). MTU negotiation is not yet exposed.
- `sendMessage()` sends to **all** connected clients simultaneously. Per-device addressing is not yet supported.

## Example app

See the [`example/`](example/) folder for a complete working demo.

## License

MIT
