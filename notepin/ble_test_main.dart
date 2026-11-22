// Offline PN0200 diagnostics entrypoint. No Firebase, login, or backend required.
// Uses the same native Android BLE host API as the full Omi application.
import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:omi/gen/pigeon_communicator.g.dart';
import 'package:omi/services/bridges/ble_bridge.dart';
import 'package:omi/services/devices/models.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  BleFlutterApi.setUp(BleBridge.instance);
  runApp(const MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'PN0200 BLE Diagnostics',
    home: NotePinBleDiagnostics(),
  ));
}

class NotePinBleDiagnostics extends StatefulWidget {
  const NotePinBleDiagnostics({super.key});

  @override
  State<NotePinBleDiagnostics> createState() => _NotePinBleDiagnosticsState();
}

class _NotePinBleDiagnosticsState extends State<NotePinBleDiagnostics> {
  final _host = BleHostApi();
  final Map<String, BlePeripheral> _found = {};
  final List<String> _logLines = [];
  bool _scanning = false;
  bool _connecting = false;
  bool _connected = false;
  bool _subscribed = false;
  String? _deviceUuid;
  String? _battery;
  Completer<List<BleService>>? _ready;
  Completer<List<int>>? _batteryReply;
  VoidCallback? _release;
  VoidCallback? _releaseScanBlocked;

  @override
  void initState() {
    super.initState();
    _append('Tryb offline: bez Firebase i serwera API.');
    _releaseScanBlocked = BleBridge.instance.addScanBlockedListener(
      (reason) => _append('Skan zablokowany: $reason'),
    );
    BleBridge.instance.peripheralDiscoveredCallback = (p) {
      if (!mounted) return;
      setState(() { _found[p.uuid] = p; });
    };
  }

  void _append(String message) {
    if (!mounted) return;
    final time = DateTime.now().toIso8601String().substring(11, 19);
    setState(() {
      _logLines.insert(0, '$time  $message');
      if (_logLines.length > 100) _logLines.removeLast();
    });
  }

  Future<void> _scan() async {
    if (_scanning || _connecting) return;
    setState(() {
      _found.clear();
      _scanning = true;
    });
    try {
      final scan = await Permission.bluetoothScan.request();
      final connect = await Permission.bluetoothConnect.request();
      if (!scan.isGranted || !connect.isGranted) {
        _append('Brak uprawnień Bluetooth (Scan/Connect).');
        return;
      }
      final state = await _host.getBluetoothState();
      _append('Bluetooth: $state');
      if (state != 'on') {
        _append('Włącz Bluetooth i ponów skanowanie.');
        return;
      }
      await _host.startScan(12, <String>[]);
      _append('Skanowanie BLE przez 12 sekund…');
      await Future<void>.delayed(const Duration(seconds: 12));
      await _host.stopScan();
      _append('Znaleziono urządzeń: ${_found.length}.');
    } catch (e) {
      _append('Błąd skanowania: $e');
    } finally {
      if (mounted) setState(() => _scanning = false);
    }
  }

  Future<void> _connect(BlePeripheral p) async {
    if (_connecting) return;
    setState(() => _connecting = true);
    try {
      await _disconnect();
      _deviceUuid = p.uuid;
      _ready = Completer<List<BleService>>();
      _release = BleBridge.instance.registerPeripheral(
        peripheralUuid: p.uuid,
        onDeviceReady: (services) {
          if (_ready != null && !_ready!.isCompleted) _ready!.complete(services);
        },
        onConnectionState: (connected, error) {
          if (!connected) {
            _append('GATT rozłączone: ${error ?? "bez kodu"}');
            if (mounted) setState(() { _connected = false; _subscribed = false; });
            if (_ready != null && !_ready!.isCompleted) {
              _ready!.completeError(StateError('GATT disconnected: $error'));
            }
          }
        },
        onCharacteristicValue: (service, char, value) {
          if (service.toLowerCase() != plaudServiceUuid.toLowerCase() ||
              char.toLowerCase() != plaudNotifyCharUuid.toLowerCase()) return;
          final raw = value.toList();
          _append('NOTIFY 2BB0: ${_hex(raw)}');
          if (raw.length >= 3 && raw[0] != 2 &&
              (raw[1] | (raw[2] << 8)) == 9) {
            if (_batteryReply != null && !_batteryReply!.isCompleted) {
              _batteryReply!.complete(raw);
            }
            if (raw.length >= 5 && mounted) {
              setState(() => _battery = '${raw[4]}%');
            }
          }
        },
      );
      _append('Łączenie GATT: ${p.name.isEmpty ? p.uuid : p.name}');
      await _host.manageDevice(p.uuid, false);
      final services = await _ready!.future.timeout(const Duration(seconds: 45));
      final plaud = services.where(
        (s) => s.uuid.toLowerCase() == plaudServiceUuid.toLowerCase(),
      ).toList();
      if (plaud.isEmpty) {
        _append('Brak serwisu PLAUD 0x1910. Nie wysyłam komend.');
        return;
      }
      final chars = plaud.first.characteristicUuids.map((x) => x.toLowerCase()).toSet();
      _append('PLAUD 1910: ${plaud.first.characteristicUuids.join(", ")}');
      if (!chars.contains(plaudNotifyCharUuid.toLowerCase()) ||
          !chars.contains(plaudWriteCharUuid.toLowerCase())) {
        _append('Brak 2BB0/2BB1. Kończę bez zapisu.');
        return;
      }
      if (mounted) setState(() => _connected = true);
      _append('Aktywuję notyfikacje 0x2BB0 (CCCD)…');
      await _host
          .subscribeCharacteristic(p.uuid, plaudServiceUuid, plaudNotifyCharUuid)
          .timeout(const Duration(seconds: 25));
      if (mounted) setState(() => _subscribed = true);
      _append('Subskrypcja 0x2BB0 potwierdzona przez Android BLE.');
    } catch (e) {
      _append('Błąd GATT/subskrypcji: $e');
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  Future<void> _batteryTest() async {
    final uuid = _deviceUuid;
    if (uuid == null || !_subscribed) return;
    _batteryReply = Completer<List<int>>();
    _append('Wysyłam CMD_GET_BATTERY (09 00) do 0x2BB1.');
    try {
      await _host.writeCharacteristic(
        uuid, plaudServiceUuid, plaudWriteCharUuid,
        Uint8List.fromList(<int>[1, 9, 0]),
      );
      final response = await _batteryReply!.future.timeout(const Duration(seconds: 10));
      _append('Odpowiedź na battery: ${_hex(response)}');
      if (response.length >= 5 && mounted) {
        setState(() => _battery = '${response[4]}%');
      }
    } catch (e) {
      _append('Brak odpowiedzi CMD_GET_BATTERY: $e');
    } finally {
      _batteryReply = null;
    }
  }

  Future<void> _disconnect() async {
    final oldUuid = _deviceUuid;
    _deviceUuid = null;
    _batteryReply = null;
    try {
      if (oldUuid != null) {
        if (_subscribed) {
          await _host.unsubscribeCharacteristic(
            oldUuid, plaudServiceUuid, plaudNotifyCharUuid,
          );
        }
        await _host.unmanageDevice(oldUuid);
      }
    } catch (e) {
      _append('Czyszczenie połączenia: $e');
    } finally {
      _release?.call();
      _release = null;
      if (mounted) setState(() { _connected = false; _subscribed = false; _battery = null; });
    }
  }

  String _hex(List<int> data) => data.take(28).map(
    (x) => x.toRadixString(16).padLeft(2, '0').toUpperCase(),
  ).join(' ');

  @override
  void dispose() {
    BleBridge.instance.peripheralDiscoveredCallback = null;
    _releaseScanBlocked?.call();
    _release?.call();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final devices = _found.values.toList()
      ..sort((a, b) {
        bool isPlaud(BlePeripheral p) =>
            p.name.toLowerCase().contains('plaud') ||
            p.name.toLowerCase().contains('notepin') ||
            p.serviceUuids.any((x) => x.toLowerCase() == plaudServiceUuid.toLowerCase());
        if (isPlaud(a) != isPlaud(b)) return isPlaud(a) ? -1 : 1;
        return b.rssi.compareTo(a.rssi);
      });
    return Scaffold(
      appBar: AppBar(title: const Text('NotePin PN0200 • BLE test')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Diagnostyka offline. Nie wymaga logowania i nie nagrywa dźwięku.'),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: (_scanning || _connecting) ? null : _scan,
                child: Text(_scanning ? 'Skanuję…' : 'Skanuj urządzenia BLE'),
              ),
              if (_connected) ...[
                Text('GATT połączony; notyfikacje: ${_subscribed ? "TAK" : "NIE"}; bateria: ${_battery ?? "?"}'),
                FilledButton(
                  onPressed: _subscribed ? _batteryTest : null,
                  child: const Text('Test odpowiedzi 0x2BB0 (bateria)'),
                ),
                TextButton(onPressed: _disconnect, child: const Text('Rozłącz')),
              ],
              const SizedBox(height: 8),
              const Text('Znalezione urządzenia (PLAUD wyżej):', style: TextStyle(fontWeight: FontWeight.bold)),
              SizedBox(
                height: 165,
                child: ListView.builder(
                  itemCount: devices.length,
                  itemBuilder: (_, i) {
                    final p = devices[i];
                    return ListTile(
                      dense: true,
                      title: Text(p.name.isEmpty ? '(bez nazwy)' : p.name),
                      subtitle: Text('${p.uuid}  RSSI: ${p.rssi}'),
                      onTap: (_connecting || _scanning) ? null : () => _connect(p),
                    );
                  },
                ),
              ),
              const Text('Dziennik zdarzeń BLE:', style: TextStyle(fontWeight: FontWeight.bold)),
              Expanded(
                child: Container(
                  color: Theme.of(context).colorScheme.surfaceContainerLowest,
                  padding: const EdgeInsets.all(8),
                  child: ListView(
                    children: _logLines.map((s) =>
                      SelectableText(s, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
                    ).toList(),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
