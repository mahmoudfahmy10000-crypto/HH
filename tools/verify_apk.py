#!/usr/bin/env python3
"""Verify the actual standalone debug APK; never changes it or bypasses device checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--apk', default='app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--output', default='app/build/outputs/delivery')
args = parser.parse_args()
apk = Path(args.apk).resolve()
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    raise SystemExit('Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory')
tools = Path(sdk) / 'build-tools' / '35.0.0'

def run(name, *arguments):
    result = subprocess.run([str(tools / name), *map(str, arguments)],
                            check=True, capture_output=True, text=True)
    return result.stdout + result.stderr

signature = run('apksigner', 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', apk)
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature
alignment = run('zipalign', '-c', '-P', '16', '-v', '4', apk)
badging = run('aapt', 'dump', 'badging', apk)
assert "package: name='com.bluewave.remote'" in badging
assert "sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
assert 'application-debuggable' in badging
package_line = next(line for line in badging.splitlines() if line.startswith('package:'))
assert ' split=' not in package_line, 'Expected a standalone APK, not a split'
permissions = sorted(re.findall(r"uses-permission: name='([^']+)'", badging))
allowed = {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE', 'android.permission.CHANGE_WIFI_MULTICAST_STATE',
           'android.permission.TRANSMIT_IR',
           'com.bluewave.remote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
assert set(permissions) <= allowed, f'Unexpected packaged permissions: {set(permissions)-allowed}'
assert {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE', 'android.permission.CHANGE_WIFI_MULTICAST_STATE',
        'android.permission.TRANSMIT_IR'} <= set(permissions)
native = []
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None, 'Corrupt ZIP entry'
    assert {'AndroidManifest.xml', 'classes.dex', 'resources.arsc'} <= set(z.namelist())
    for name in z.namelist():
        if not name.endswith('.so'):
            continue
        data = z.read(name)
        assert data[:4] == b'\x7fELF' and data[5] == 1, 'Expected little-endian ELF'
        if data[4] == 2:
            offset = struct.unpack_from('<Q', data, 32)[0]
            entry_size, count = struct.unpack_from('<HH', data, 54)
            fields = '<IIQQQQQQ'
        else:
            assert data[4] == 1
            offset = struct.unpack_from('<I', data, 28)[0]
            entry_size, count = struct.unpack_from('<HH', data, 42)
            fields = '<IIIIIIII'
        loads = []
        for i in range(count):
            entry = struct.unpack_from(fields, data, offset + i * entry_size)
            if entry[0] == 1:  # PT_LOAD
                align = entry[-1]
                file_offset = entry[2] if data[4] == 2 else entry[1]
                address = entry[3] if data[4] == 2 else entry[2]
                assert align >= 16384 and (address-file_offset) % 16384 == 0, f'Non-16KB ELF: {name}'
                loads.append(align)
        assert loads
        native.append({'path': name, 'load_segment_alignment': loads,
                       'zip_compression': z.getinfo(name).compress_type})
assert any(x['path'].startswith('lib/arm64-v8a/') for x in native), 'Missing ARM64 library'
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
certificate_sha256 = re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', signature)
assert certificate_sha256
summary = {'file': 'app-debug.apk', 'sha256': sha, 'size_bytes': apk.stat().st_size,
           'package': 'com.bluewave.remote', 'min_sdk': 26, 'target_sdk': 35,
           'version_name': re.search(r"versionName='([^']+)'", package_line).group(1),
           'version_code': int(re.search(r"versionCode='([^']+)'", package_line).group(1)),
           'standalone': True, 'debuggable': True, 'signature_v2': True, 'signature_v3': True,
           'signer_certificate_sha256': certificate_sha256.group(1),
           'permissions': permissions, 'zip_crc_valid': True,
           'zipalign_16kb_valid': True, 'native_libraries': native,
           'limitations': 'Static/build verification, not Play Protect approval or physical-device testing.'}
if apk != out / 'app-debug.apk':
    shutil.copy2(apk, out / 'app-debug.apk')
(out / 'verification.json').write_text(json.dumps(summary, indent=2)+'\n')
(out / 'signature.txt').write_text(signature)
(out / 'alignment.txt').write_text(alignment)
(out / 'manifest-badging.txt').write_text(badging)
(out / 'SHA256SUMS').write_text(sha+'  app-debug.apk\n')
for report in [Path('SECURITY_AUDIT.md'), Path('AU7000_GUIDE.md'), Path('BUTTON_AUDIT.md'), Path('COMPATIBILITY_FIX.md')]:
    if report.exists():
        shutil.copy2(report, out / report.name)
for evidence in Path('audit').glob('*.json'):
    shutil.copy2(evidence, out / evidence.name)
print(json.dumps(summary, indent=2))
