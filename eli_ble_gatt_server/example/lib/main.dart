import 'package:flutter/material.dart';
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:eli_ble_gatt_server/eli_ble_gatt_server_events.dart';


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
  String _platformVersion = 'Unknown';
  late StreamSubscription sub;


  @override
  void initState() {
    super.initState();
    sub = EliBleGattServer.events.listen((event) {
      switch (event) {
        case BleAdvertisingEvent e:
          print("Advertising: ${e.status}");
          break;

        case BleDeviceConnectedEvent e:
          print("Connected: ${e.name} (${e.address}) total=${e.total}");
          break;

        case BleDeviceDisconnectedEvent e:
          print("Disconnected: ${e.address} total=${e.total}");
          break;

        case BleRxEvent e:
          print("RX from ${e.from}: ${e.data}");
          break;

        case BleTxEvent e:
          print("TX chunk=${e.chunkSize} offset=${e.offset}/${e.total}");
          break;

        case BleServerInfoEvent e:
          print("Server info: ${e.deviceName} active=${e.serverActive}");
          break;

        case BleServiceDestroyedEvent _:
          print("Service destroyed");
          break;

        default:
          print("Unknown event: ${event.type}");
      }
    });

  
    initPlatformState();
  }


  @override
  void dispose() {
    sub.cancel();
    super.dispose();
  }

  Future<void> initPlatformState() async {
    String platformVersion;

    

    try {
      platformVersion =
          await EliBleGattServer.getPlatformVersion() ??
              'Unknown platform version';
    } on PlatformException {
      platformVersion = 'Failed to get platform version.';
    }

    if (!mounted) return;

    setState(() {
      _platformVersion = platformVersion;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Running on: $_platformVersion\n'),

              ElevatedButton(
                onPressed: () async {
                  await requestBlePermissions();

                  await EliBleGattServer.configure_and_start(
                    serviceUuid: '0000FFF0-0000-1000-8000-00805f9b34fb',   // Service
                    characteristicUuid: '0000FFF1-0000-1000-8000-00805f9b34fb', // Characteristic
                    deviceName: 'EliBLE',
                    payload: {
                      'msg': 'Hola desde Flutter',
                      'value': 42,
                    },
                  );

                },
                child: const Text('Start BLE GATT SERVER'),
              ),


              ElevatedButton(
                onPressed: () async {
                  await EliBleGattServer.stop();
                },
                child: const Text('Stop Server'),
              ),
            ],
          ),
        ),

      ),
    );
  }
}
