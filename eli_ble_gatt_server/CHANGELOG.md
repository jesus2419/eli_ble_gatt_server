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
