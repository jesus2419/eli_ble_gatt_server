
class BleAdvertisingEvent extends BleEvent {
  final String status;
  BleAdvertisingEvent({required this.status}) : super("advertising");
}

class BleDeviceConnectedEvent extends BleEvent {
  final String address;
  final String name;
  final int total;

  BleDeviceConnectedEvent({
    required this.address,
    required this.name,
    required this.total,
  }) : super("device_connected");
}

class BleDeviceDisconnectedEvent extends BleEvent {
  final String address;
  final int total;

  BleDeviceDisconnectedEvent({
    required this.address,
    required this.total,
  }) : super("device_disconnected");
}

class BleRxEvent extends BleEvent {
  final String from;
  final String data;

  BleRxEvent({
    required this.from,
    required this.data,
  }) : super("ble_rx");
}

class BleTxEvent extends BleEvent {
  final int chunkSize;
  final int offset;
  final int total;

  BleTxEvent({
    required this.chunkSize,
    required this.offset,
    required this.total,
  }) : super("ble_tx");
}

class BleServerInfoEvent extends BleEvent {
  final String? deviceName;
  final String serviceUuid;
  final String characteristicUuid;
  final bool advertising;
  final bool serverActive;
  final int connectedDevices;

  BleServerInfoEvent({
    required this.deviceName,
    required this.serviceUuid,
    required this.characteristicUuid,
    required this.advertising,
    required this.serverActive,
    required this.connectedDevices,
  }) : super("server_info");
}

class BleServiceDestroyedEvent extends BleEvent {
  const BleServiceDestroyedEvent() : super("service_destroyed");
}

class BleUnknownEvent extends BleEvent {
  final dynamic raw;
  BleUnknownEvent(this.raw) : super("unknown");
}


abstract class BleEvent {
  final String type;
  const BleEvent(this.type);

  factory BleEvent.from(dynamic event) {
    if (event is! Map) {
      return BleUnknownEvent(event);
    }

    final map = Map<String, dynamic>.from(event);
    final type = map['type'];

    switch (type) {
      case 'advertising':
        return BleAdvertisingEvent(
          status: map['status'] ?? 'unknown',
        );

      case 'device_connected':
        return BleDeviceConnectedEvent(
          address: map['address'] ?? '',
          name: map['name'] ?? 'unknown',
          total: map['total'] ?? 0,
        );

      case 'device_disconnected':
        return BleDeviceDisconnectedEvent(
          address: map['address'] ?? '',
          total: map['total'] ?? 0,
        );

      case 'ble_rx':
        return BleRxEvent(
          from: map['from'] ?? '',
          data: map['data'] ?? '',
        );

      case 'ble_tx':
        return BleTxEvent(
          chunkSize: map['chunk_size'] ?? 0,
          offset: map['offset'] ?? 0,
          total: map['total'] ?? 0,
        );

      case 'server_info':
        return BleServerInfoEvent(
          deviceName: map['device_name'],
          serviceUuid: map['service_uuid'] ?? '',
          characteristicUuid: map['characteristic_uuid'] ?? '',
          advertising: map['advertising'] ?? false,
          serverActive: map['server_active'] ?? false,
          connectedDevices: map['connected_devices'] ?? 0,
        );

      case 'service_destroyed':
        return const BleServiceDestroyedEvent();

      default:
        return BleUnknownEvent(map);
    }
  }
}
