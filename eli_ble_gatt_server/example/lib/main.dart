import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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

const _serviceUuid = '0000FFF0-0000-1000-8000-00805F9B34FB';
const _characteristicUuid = '0000FFF1-0000-1000-8000-00805F9B34FB';

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => const MaterialApp(
        title: 'eli_ble_gatt_server demo',
        home: BleServerPage(),
      );
}

class _LogEntry {
  final DateTime time;
  final String text;
  final Color color;
  _LogEntry(this.text, this.color) : time = DateTime.now();
}

class BleServerPage extends StatefulWidget {
  const BleServerPage({super.key});

  @override
  State<BleServerPage> createState() => _BleServerPageState();
}

class _BleServerPageState extends State<BleServerPage> {
  StreamSubscription<BleEvent>? _sub;
  final List<_LogEntry> _log = [];
  final TextEditingController _msgCtrl = TextEditingController();
  final ScrollController _scrollCtrl = ScrollController();

  bool _serverRunning = false;
  int _connectedDevices = 0;
  bool _sending = false;

  // ---------- lifecycle ----------

  @override
  void initState() {
    super.initState();
    _sub = EliBleGattServer.events.listen(_onEvent);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _msgCtrl.dispose();
    _scrollCtrl.dispose();
    super.dispose();
  }

  // ---------- event handler ----------

  void _onEvent(BleEvent event) {
    String text;
    Color color;

    switch (event) {
      case BleAdvertisingEvent e:
        text = '📡 Advertising: ${e.status}';
        color = Colors.blue;

      case BleDeviceConnectedEvent e:
        text = '🔗 Connected: ${e.address} (total: ${e.total})';
        color = Colors.green;
        setState(() {
          _connectedDevices = e.total;
          _serverRunning = true;
        });

      case BleDeviceDisconnectedEvent e:
        text = '🔌 Disconnected: ${e.address} (remaining: ${e.total})';
        color = Colors.orange;
        setState(() => _connectedDevices = e.total);

      case BleRxEvent e:
        text = '⬇️  RX from ${e.from}:\n    ${e.data}';
        color = Colors.teal;

      case BleTxEvent e:
        text = '⬆️  TX chunk ${e.offset}/${e.total} (${e.chunkSize} B)';
        color = Colors.purple;

      case BleServerInfoEvent e:
        text =
            'ℹ️  Server — active=${e.serverActive} advertising=${e.advertising} devices=${e.connectedDevices}';
        color = Colors.indigo;
        setState(() {
          _serverRunning = e.serverActive;
          _connectedDevices = e.connectedDevices;
        });

      case BleServiceDestroyedEvent _:
        text = '🛑 Server stopped';
        color = Colors.red;
        setState(() {
          _serverRunning = false;
          _connectedDevices = 0;
        });

      default:
        text = '❓ Unknown: ${event.type}';
        color = Colors.grey;
    }

    _addLog(text, color);
  }

  void _addLog(String text, Color color) {
    setState(() => _log.add(_LogEntry(text, color)));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  // ---------- actions ----------

  Future<void> _startServer() async {
    await requestBlePermissions();
    try {
      await EliBleGattServer.configureAndStart(
        serviceUuid: _serviceUuid,
        characteristicUuid: _characteristicUuid,
        deviceName: 'EliBLE',
        payload: {
          'msg': 'Hello from Flutter BLE Server',
          'version': 1,
        },
      );
      setState(() => _serverRunning = true);
      _addLog('✅ Server started', Colors.green);
    } on PlatformException catch (e) {
      _addLog('❌ Start failed: ${e.message}', Colors.red);
    }
  }

  Future<void> _stopServer() async {
    try {
      await EliBleGattServer.stop();
      setState(() {
        _serverRunning = false;
        _connectedDevices = 0;
      });
      _addLog('🛑 Stop requested', Colors.orange);
    } on PlatformException catch (e) {
      _addLog('❌ Stop failed: ${e.message}', Colors.red);
    }
  }

  Future<void> _sendMessage() async {
    final msg = _msgCtrl.text.trim();
    if (msg.isEmpty) return;
    if (!_serverRunning) {
      _addLog('⚠️  Server is not running', Colors.orange);
      return;
    }
    if (_connectedDevices == 0) {
      _addLog('⚠️  No devices connected', Colors.orange);
      return;
    }

    setState(() => _sending = true);
    _addLog('⬆️  Sending: "$msg" → $_connectedDevices device(s)', Colors.purple);

    try {
      await EliBleGattServer.sendMessage(msg);
      _msgCtrl.clear();
    } on PlatformException catch (e) {
      _addLog('❌ Send failed: ${e.message}', Colors.red);
    } finally {
      setState(() => _sending = false);
    }
  }

  Future<void> _queryStatus() async {
    try {
      final s = await EliBleGattServer.getServerStatus();
      _addLog(
        'ℹ️  Status — active=${s['isActive']} advertising=${s['advertising']} '
        'devices=${s['connectedDevices']} name=${s['deviceName']}',
        Colors.indigo,
      );
    } on PlatformException catch (e) {
      _addLog('❌ Status failed: ${e.message}', Colors.red);
    }
  }

  // ---------- UI ----------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('eli_ble_gatt_server'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: Chip(
              avatar: Icon(
                _serverRunning ? Icons.bluetooth_connected : Icons.bluetooth_disabled,
                size: 16,
                color: _serverRunning ? Colors.green : Colors.grey,
              ),
              label: Text(
                _serverRunning
                    ? '$_connectedDevices device(s)'
                    : 'Stopped',
                style: const TextStyle(fontSize: 12),
              ),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          // Control buttons
          Padding(
            padding: const EdgeInsets.all(8),
            child: Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                ElevatedButton.icon(
                  onPressed: _serverRunning ? null : _startServer,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Start Server'),
                ),
                ElevatedButton.icon(
                  onPressed: _serverRunning ? _stopServer : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('Stop Server'),
                  style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.red,
                      foregroundColor: Colors.white),
                ),
                OutlinedButton.icon(
                  onPressed: _queryStatus,
                  icon: const Icon(Icons.info_outline),
                  label: const Text('Status'),
                ),
                OutlinedButton.icon(
                  onPressed: () => setState(() => _log.clear()),
                  icon: const Icon(Icons.clear_all),
                  label: const Text('Clear log'),
                ),
              ],
            ),
          ),

          const Divider(height: 1),

          // Send message row
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _msgCtrl,
                    decoration: const InputDecoration(
                      hintText: 'Message to send to all connected devices…',
                      border: OutlineInputBorder(),
                      isDense: true,
                      contentPadding:
                          EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    ),
                    onSubmitted: (_) => _sendMessage(),
                    textInputAction: TextInputAction.send,
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.icon(
                  onPressed: _sending ? null : _sendMessage,
                  icon: _sending
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white),
                        )
                      : const Icon(Icons.send),
                  label: const Text('Send'),
                ),
              ],
            ),
          ),

          const Divider(height: 1),

          // Event log
          Expanded(
            child: _log.isEmpty
                ? const Center(
                    child: Text(
                      'No events yet.\nStart the server to see activity here.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.grey),
                    ),
                  )
                : ListView.builder(
                    controller: _scrollCtrl,
                    padding: const EdgeInsets.all(8),
                    itemCount: _log.length,
                    itemBuilder: (_, i) {
                      final entry = _log[i];
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 2),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '${entry.time.hour.toString().padLeft(2, '0')}:'
                              '${entry.time.minute.toString().padLeft(2, '0')}:'
                              '${entry.time.second.toString().padLeft(2, '0')} ',
                              style: TextStyle(
                                  fontSize: 11, color: Colors.grey[600]),
                            ),
                            Expanded(
                              child: Text(
                                entry.text,
                                style: TextStyle(
                                    fontSize: 12, color: entry.color),
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
