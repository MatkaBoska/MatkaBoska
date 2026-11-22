#!/usr/bin/env python3
"""Apply a guarded, reversible PLAUD NotePin BLE experiment to Omi source."""
from pathlib import Path

path = Path('omi-source/app/lib/services/devices/connectors/plaud_connection.dart')
if not path.is_file():
    raise SystemExit(f'Brak pliku Omi: {path}')
source = path.read_text(encoding='utf-8')


def replace_once(before: str, after: str, label: str) -> None:
    global source
    found = source.count(before)
    if found != 1:
        raise SystemExit(f'Nie zastosowano {label}: oczekiwano 1 dopasowania, znaleziono {found}. Kod Omi mógł się zmienić.')
    source = source.replace(before, after, 1)


replace_once(
    """    await super.connect(onConnectionStateChanged: onConnectionStateChanged);
    await Future.delayed(const Duration(seconds: 2));

    final stream = transport.getCharacteristicStream(plaudServiceUuid, plaudNotifyCharUuid);
    _notificationSub = stream.listen(_handleNotification);""",
    """    await super.connect(onConnectionStateChanged: onConnectionStateChanged);

    // Start native CCCD subscription before waiting for device readiness.
    // The previous delay happened BEFORE subscription and thus did not help.
    final stream = transport.getCharacteristicStream(plaudServiceUuid, plaudNotifyCharUuid);
    _notificationSub = stream.listen(_handleNotification);
    await Future.delayed(const Duration(seconds: 2));""",
    'kolejność subskrypcji 0x2BB0',
)

replace_once(
    """  Future<List<int>?> _sendCommand(int cmdId, List<int> payload) async {
    _commandQueues.putIfAbsent(cmdId, () => StreamController<List<int>>.broadcast());

    final command = [1, cmdId & 0xFF, (cmdId >> 8) & 0xFF, ...payload];
    await transport.writeCharacteristic(plaudServiceUuid, plaudWriteCharUuid, command);

    try {
      return await _commandQueues[cmdId]!.stream.first.timeout(const Duration(seconds: 10));
    } on TimeoutException {
      return null;
    }
  }""",
    """  Future<List<int>?> _sendCommand(int cmdId, List<int> payload) async {
    final queue = _commandQueues.putIfAbsent(cmdId, () => StreamController<List<int>>.broadcast());
    final response = Completer<List<int>>();
    // Register response listener BEFORE sending the BLE command. Otherwise
    // a quick NotePin reply can be lost by the broadcast StreamController.
    final subscription = queue.stream.listen((data) {
      if (!response.isCompleted) response.complete(data);
    });

    try {
      final command = [1, cmdId & 0xFF, (cmdId >> 8) & 0xFF, ...payload];
      await transport.writeCharacteristic(plaudServiceUuid, plaudWriteCharUuid, command);
      return await response.future.timeout(const Duration(seconds: 10));
    } on TimeoutException {
      Logger.debug('[PLAUD] Timed out waiting for BLE command response: $cmdId');
      return null;
    } finally {
      await subscription.cancel();
    }
  }""",
    'nasłuchiwanie odpowiedzi przed wysłaniem WRITE',
)

replace_once(
    """    if (response != null && response.length >= 10) {
      return {'sessionId': _toInt32(response.sublist(0, 4)), 'startTime': _toInt32(response.sublist(4, 8))};""",
    """    if (response != null && response.length >= 8) {
      return {'sessionId': _toInt32(response.sublist(0, 4)), 'startTime': _toInt32(response.sublist(4, 8))};""",
    'minimalna długość odpowiedzi start-record',
)

path.write_text(source, encoding='utf-8')
print(f'Zastosowano trzy poprawki diagnostyczne w {path}')


# Android 14+ regression experiment: Omi currently reconstructs every BLE
# peripheral as ADDRESS_TYPE_RANDOM. The ScanResult BluetoothDevice carries
# Android's observed address type. Reuse that instance for PLAUD only.
native = Path('omi-source/app/android/app/src/main/kotlin/com/friend/ios/ble/OmiBleManager.kt')
if not native.is_file():
    raise SystemExit(f'Brak pliku Android Omi: {native}')
kotlin = native.read_text(encoding='utf-8')


def replace_kotlin_once(before: str, after: str, label: str) -> None:
    global kotlin
    n = kotlin.count(before)
    if n != 1:
        raise SystemExit(f'Nie zastosowano {label}: oczekiwano 1 dopasowania, znaleziono {n}.')
    kotlin = kotlin.replace(before, after, 1)


replace_kotlin_once(
    """    val mainHandler = Handler(Looper.getMainLooper())""",
    """    val mainHandler = Handler(Looper.getMainLooper())

    // Temporary PN0200 test: Android scan results retain address-type metadata.
    // Hardcoding ADDRESS_TYPE_RANDOM for every device can misaddress a PUBLIC
    // advertiser. Cache PLAUD scan handles and reuse the exact BluetoothDevice.
    private val plaudScannedDevices = ConcurrentHashMap<String, BluetoothDevice>()""",
    'cache zidentyfikowanych urządzeń PLAUD',
)

replace_kotlin_once(
    """                val rssi = result.rssi
                val advServiceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()""",
    """                val rssi = result.rssi
                val advServiceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                if (name.contains("plaud", ignoreCase = true) ||
                    name.contains("notepin", ignoreCase = true)) {
                    plaudScannedDevices[address] = device
                }""",
    'zapamiętanie ScanResult.device',
)

replace_kotlin_once(
    """        // Use getRemoteLeDevice with ADDRESS_TYPE_RANDOM to specify the correct address type.
        val device = if (android.os.Build.VERSION.SDK_INT >= 34) {
            adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        } else {
            adapter.getRemoteDevice(addr)
        }
        val callback = createGattCallback()""",
    """        // Prefer the freshly scanned BluetoothDevice: it remembers whether the
        // advertiser used a public or a random address. Leave the existing
        // fallback unchanged for unscanned devices.
        val scannedDevice = plaudScannedDevices[addr]
        val device = scannedDevice ?: if (android.os.Build.VERSION.SDK_INT >= 34) {
            adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        } else {
            adapter.getRemoteDevice(addr)
        }
        Log.i(TAG, "PN0200 GATT address source: " +
            if (scannedDevice != null) "ScanResult.device" else "legacy fallback")
        val callback = createGattCallback()""",
    'użycie prawdziwego BluetoothDevice ze skanowania',
)

native.write_text(kotlin, encoding='utf-8')
print(f'PN0200: poprawiono też wybór typu adresu BLE w {native}')
