#!/usr/bin/env python3
"""Apply Android-only NotePin ChatGPT bridge to a pinned MIT upstream checkout.

Changes only the cloned workspace during CI; never reads secrets or vendor files.
"""
from pathlib import Path
import shutil

root = Path('notepin-live-audio')
if not root.is_dir():
    raise SystemExit(f'Upstream checkout missing: {root}')
main = root / 'android/app/src/main/java/dev/notepin/poc/MainActivity.java'
manifest = root / 'android/app/src/main/AndroidManifest.xml'
ble = root / 'android/app/src/main/java/dev/notepin/poc/BleLink.java'
dest = root / 'android/app/src/main/java/dev/notepin/poc/ChatBridgeActivity.java'
for path in (main, manifest, ble):
    if not path.is_file():
        raise SystemExit(f'Upstream source missing: {path}')
if dest.exists():
    raise SystemExit('ChatBridgeActivity already exists in upstream checkout.')


def replace_once(path: Path, before: str, after: str) -> None:
    source = path.read_text(encoding='utf-8')
    n = source.count(before)
    if n != 1:
        raise SystemExit(f'Upstream changed; expected one anchor in {path}, found {n}.')
    path.write_text(source.replace(before, after, 1), encoding='utf-8')


replace_once(
    main,
    '    Button battery = new Button(this); battery.setText("端末のバックグラウンド設定を開く"); layout.addView(battery);',
    '''    Button ai = new Button(this);
    ai.setText("ChatGPT: transkrypcja WAV i pytania");
    layout.addView(ai);
    ai.setOnClickListener(v -> startActivity(new Intent(this, ChatBridgeActivity.class)));
    Button battery = new Button(this); battery.setText("端末のバックグラウンド設定を開く"); layout.addView(battery);''',
)
replace_once(
    manifest,
    '  <activity android:name=".MainActivity" android:exported="true">',
    '  <activity android:name=".ChatBridgeActivity" android:exported="false"/>\n  <activity android:name=".MainActivity" android:exported="true">',
)
# PN0200: preserve the BluetoothDevice returned from Android scan, instead of
# reconstructing an address-typeless device handle. Reuses the successful Omi
# experiment without changing the vendor authentication protocol.
replace_once(
    ble,
    '      BluetoothDevice device = adapter.getRemoteDevice(address);',
    '''      BluetoothDevice device = adapter.getRemoteDevice(address);
      // Android 14+: the scan's BluetoothDevice holds the correct address type.
      // A fresh BLE scan avoids hardcoded/random-address assumptions on PN0200.
      android.bluetooth.le.BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
      if (scanner != null) {
        java.util.concurrent.atomic.AtomicReference<BluetoothDevice> observed =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch found = new java.util.concurrent.CountDownLatch(1);
        android.bluetooth.le.ScanCallback scanCallback = new android.bluetooth.le.ScanCallback() {
          @Override public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
            if (result.getDevice().getAddress().equalsIgnoreCase(address)) {
              observed.set(result.getDevice());
              found.countDown();
            }
          }
        };
        try {
          scanner.startScan(scanCallback);
          found.await(8, TimeUnit.SECONDS);
        } finally {
          try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        if (observed.get() != null) device = observed.get();
      }''',
)
# Make the primary control labels usable for a Polish-speaking tester.
# Leave developer/status internals unchanged to preserve original diagnostics.
primary_labels = {
    '認証JSONを取り込む': 'Importuj JSON właściciela PLAUD',
    '自動回収を開始（再起動後も再開）': 'Rozpocznij pobieranie nagrań',
    '回収停止（機器の録音は継続）': 'Zatrzymaj pobieranie (NotePin nagrywa dalej)',
    'PCへのWebSocket転送を設定／停止': 'Transmisja WebSocket na komputer',
    '端末のバックグラウンド設定を開く': 'Ustawienia baterii i działania w tle',
    '保存済みWAVをフォルダーへ書き出す': 'Eksportuj pobrane WAV',
    '先に認証JSONを取り込んでください': 'Najpierw zaimportuj JSON właściciela.',
    '未開始': 'Nie uruchomiono',
}
main_text = main.read_text(encoding='utf-8')
for before, after in primary_labels.items():
    n = main_text.count(before)
    if n < 1:
        raise SystemExit(f'Missing Polish UI text anchor: {before}')
    main_text = main_text.replace(before, after)
main.write_text(main_text, encoding='utf-8')

shutil.copyfile('notepin/chatgpt/ChatBridgeActivity.java', dest)
print('NotePin bridge patched: activity, launcher, manifest, fresh BLE scan.')
