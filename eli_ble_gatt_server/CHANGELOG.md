## 0.2.0

### New features
- **iOS support** 🎉 The plugin now runs as a BLE GATT server (peripheral) on iOS 13+
  using CoreBluetooth (`CBPeripheralManager`). The Dart API and event contract are
  identical to Android — no code changes are required in your app.
- Re-declared the `ios` platform in `pubspec.yaml` (`pluginClass: EliBleGattServerPlugin`),
  now backed by a full native implementation.

### iOS implementation notes
- Mirrors the Android feature set: advertising, READ/WRITE/NOTIFY characteristic,
  automatic welcome `payload` on subscription, 20-byte NOTIFY chunking with `BleTxEvent`,
  and the full real-time event stream.
- TX chunk pacing uses CoreBluetooth transmit-queue backpressure
  (`peripheralManagerIsReadyToUpdateSubscribers`) instead of Android's fixed 30 ms delay.

### iOS limitations & differences vs Android
- **Foreground only** — no background execution / persistent notification. `isActive`
  reflects that the peripheral is powered on and advertising.
- **No device-name change** — `deviceName` is used only as the advertised local name
  (`CBAdvertisementDataLocalNameKey`), and iOS omits it while backgrounded.
- **No MAC / central name** — centrals are identified by their `identifier` (UUID), so
  `address`/`from` is that UUID and `name` is always `"unknown"`.
- **No manufacturer data** in the peripheral advertisement.
- **"Connection" = notification subscription** — `device_connected` fires when a central
  subscribes (also when the welcome `payload` is delivered); `device_disconnected` on unsubscribe.
- Requires a **real device** — CoreBluetooth's peripheral role is unavailable on the iOS Simulator.

### Setup
- iOS apps must add `NSBluetoothAlwaysUsageDescription` to `ios/Runner/Info.plist`
  (otherwise the app crashes when CoreBluetooth starts on iOS 13+).

---

## 0.1.0

### Breaking changes
- Renamed `configure_and_start()` → `configureAndStart()` (camelCase, Dart conventions).
- Removed `getPlatformVersion()` from the public API (internal scaffolding method).

### New features
- **`payload` now works**: the map passed to `configure()` / `configureAndStart()` is
  serialized to JSON and sent as the first notification to each client that enables
  notifications on the characteristic (CCCD write).
- **`getServerStatus()`**: new method that returns the current server state without
  relying on the event stream. Returns `isActive`, `advertising`, `connectedDevices`,
  `deviceName`, `serviceUuid`, and `characteristicUuid`.
- **`sendMessage(String message)`**: send an arbitrary text notification to all currently
  connected BLE clients at any time. The message is split into 20-byte chunks
  automatically and each chunk emits a `BleTxEvent` on the event stream. Throws
  `PlatformException(SERVICE_NOT_RUNNING)` if called when the server is stopped.

### Bug fixes
- **Fixed double GATT response**: `onCharacteristicWriteRequest` was calling
  `sendResponse()` twice, violating the BLE protocol and causing connection instability.

### Improvements
- Completed the `PlatformInterface` abstraction: all public methods (`configure`, `start`,
  `stop`, `events`, `getServerStatus`) are now declared in `EliBleGattServerPlatform`
  and implemented in `MethodChannelEliBleGattServer`. This makes the plugin truly
  modular and mockable for testing.
- Removed iOS from `pubspec.yaml` `flutter.plugin.platforms` — it was declared but had
  no native implementation, which could cause crashes when running on iOS.
- Updated `description` and `homepage` in `pubspec.yaml` for pub.dev compliance.
- Added `export` of `eli_ble_gatt_server_events.dart` from the main entry point so
  consumers only need one import.

### Notes
- Android only. iOS support is planned for a future release.
- Requires Android API 21+ with BLE Peripheral Mode hardware support.

---

## 0.0.1

- Initial release of `eli_ble_gatt_server`.
- Android BLE GATT Server implementation running as a Foreground Service.
- Support for custom Service UUID, Characteristic UUID, and device name.
- Characteristic configured with READ / WRITE / NOTIFY properties.
- Real-time event streaming to Flutter using `EventChannel`.
- Typed event system (`BleEvent`) with classes for all event types.
- Flutter API: `configure()`, `configure_and_start()`, `start()`, `stop()`.
