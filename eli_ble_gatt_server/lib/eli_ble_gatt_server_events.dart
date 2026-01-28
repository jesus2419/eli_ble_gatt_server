
import 'package:flutter/foundation.dart';

class EliBleEventLogger {
  static void handle(dynamic event) {
    if (event is! Map) {
      debugPrint('[BLE][UNKNOWN] Evento no válido: $event');
      return;
    }

    final type = event['type'];

    switch (type) {
      case 'advertising':
        _logAdvertising(event);
        break;

      case 'server_info':
        _logServerInfo(event);
        break;

      case 'device_connected':
        _logDeviceConnected(event);
        break;

      case 'device_disconnected':
        _logDeviceDisconnected(event);
        break;

      case 'ble_tx':
        _logBleTx(event);
        break;

      case 'ble_rx':
        _logBleRx(event);
        break;

      case 'service_destroyed':
        _logServiceDestroyed();
        break;

      default:
        debugPrint('[BLE][UNHANDLED] $event');
    }
  }

  // ─────────────────────────────
  // LOG IMPLEMENTATIONS
  // ─────────────────────────────

  static void _logAdvertising(Map event) {
    final status = event['status'];

    if (status == 'started') {
      debugPrint('📡 [BLE] Advertising STARTED');
    } else if (status == 'error') {
      debugPrint('❌ [BLE] Advertising ERROR → code: ${event['code']}');
    }
  }

  static void _logServerInfo(Map event) {
    debugPrint(
      '🖥 [BLE] Server Info\n'
      '• Device: ${event['device_name']}\n'
      '• Service UUID: ${event['service_uuid']}\n'
      '• Characteristic UUID: ${event['characteristic_uuid']}\n'
      '• Advertising: ${event['advertising']}\n'
      '• Active: ${event['server_active']}\n'
      '• Connected: ${event['connected_devices']}',
    );
  }

  static void _logDeviceConnected(Map event) {
    debugPrint(
      '🔌 [BLE] Device CONNECTED\n'
      '• Name: ${event['name']}\n'
      '• Address: ${event['address']}\n'
      '• Total: ${event['total']}',
    );
  }

  static void _logDeviceDisconnected(Map event) {
    debugPrint(
      '🔌 [BLE] Device DISCONNECTED\n'
      '• Address: ${event['address']}\n'
      '• Total: ${event['total']}',
    );
  }

  static void _logBleTx(Map event) {
    debugPrint(
      '📤 [BLE] TX Chunk\n'
      '• Size: ${event['chunk_size']} bytes\n'
      '• Offset: ${event['offset']}/${event['total']}',
    );
  }

  static void _logBleRx(Map event) {
    debugPrint(
      '📥 [BLE] RX Data\n'
      '• From: ${event['from']}\n'
      '• Data: ${event['data']}',
    );
  }

  static void _logServiceDestroyed() {
    debugPrint('🧨 [BLE] Service DESTROYED');
  }
}
