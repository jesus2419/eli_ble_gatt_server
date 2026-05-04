import 'dart:async';

import 'package:flutter/material.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

Future<void> requestBlePermissions() async {
  if (await Permission.bluetoothConnect.isDenied ||
      await Permission.bluetoothAdvertise.isDenied) {
    await [
      Permission.bluetoothConnect,
      Permission.bluetoothAdvertise,
    ].request();
  }
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final List<String> _log = [];
  late final StreamSubscription<BleEvent> _sub;

  @override
  void initState() {
    super.initState();
    _sub = EliBleGattServer.events.listen((event) {
      String message;
      switch (event) {
        case BleAdvertisingEvent e:
          message = 'Advertising: ${e.status}';
        case BleDeviceConnectedEvent e:
          message = 'Connected: ${e.name} (${e.address}) total=${e.total}';
        case BleDeviceDisconnectedEvent e:
          message = 'Disconnected: ${e.address} remaining=${e.total}';
        case BleRxEvent e:
          message = 'RX from ${e.from}: ${e.data}';
        case BleTxEvent e:
          message = 'TX chunk=${e.chunkSize} offset=${e.offset}/${e.total}';
        case BleServerInfoEvent e:
          message =
              'Server info — active=${e.serverActive} devices=${e.connectedDevices}';
        case BleServiceDestroyedEvent _:
          message = 'Service destroyed';
        default:
          message = 'Unknown event: ${event.type}';
      }
      setState(() => _log.insert(0, message));
    });
  }

  @override
  void dispose() {
    _sub.cancel();
    super.dispose();
  }

  Future<void> _startServer() async {
    await requestBlePermissions();
    await EliBleGattServer.configureAndStart(
      serviceUuid: '0000FFF0-0000-1000-8000-00805F9B34FB',
      characteristicUuid: '0000FFF1-0000-1000-8000-00805F9B34FB',
      deviceName: 'EliBLE',
      payload: {
        'msg': 'Hello from Flutter',
        'version': 1,
      },
    );
  }

  Future<void> _stopServer() async {
    await EliBleGattServer.stop();
  }

  Future<void> _queryStatus() async {
    final status = await EliBleGattServer.getServerStatus();
    setState(() => _log.insert(0,
        'Status — active=${status['isActive']} devices=${status['connectedDevices']}'));
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('eli_ble_gatt_server example')),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(12),
              child: Wrap(
                spacing: 8,
                children: [
                  ElevatedButton(
                    onPressed: _startServer,
                    child: const Text('Start Server'),
                  ),
                  ElevatedButton(
                    onPressed: _stopServer,
                    child: const Text('Stop Server'),
                  ),
                  OutlinedButton(
                    onPressed: _queryStatus,
                    child: const Text('Get Status'),
                  ),
                ],
              ),
            ),
            const Divider(),
            Expanded(
              child: ListView.builder(
                reverse: false,
                itemCount: _log.length,
                itemBuilder: (_, i) => Padding(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
                  child: Text(
                    _log[i],
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
