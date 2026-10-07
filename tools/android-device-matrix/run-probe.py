"""Capture bounded OS results from this checkout's empty, audio-disabled AVD only.

This developer harness does not verify OEM phones, normal user builds, pairing,
automatic calls, real WhatsApp audio or microphone audibility.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--sdk', type=int, choices=[33, 34, 35, 36], required=True)
parser.add_argument('--jar', type=Path, required=True)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--avd-dir', type=Path,
                    help='Must be this checkout artifacts/avd-home/wa-reco-apiSDK.avd.')
parser.add_argument('--expected-apk-sha256', help='Optional independently verified release digest.')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Physical recording probes require a separate owner-operated procedure.')
if args.expected_apk_sha256 and not re.fullmatch(r'[a-fA-F0-9]{64}', args.expected_apk_sha256):
    parser.error('--expected-apk-sha256 must contain exactly 64 hexadecimal characters.')
if not args.jar.is_file() or not args.apk.is_file():
    parser.error('Both --jar and --apk must be existing regular files.')
expected_avd = root / 'artifacts/avd-home' / f'wa-reco-api{args.sdk}.avd'
avd_dir = (args.avd_dir or expected_avd).resolve()
if avd_dir != expected_avd.absolute() or not avd_dir.is_dir():
    parser.error('--avd-dir must resolve to this checkout\'s exact existing owned AVD directory.')
output = args.output.resolve()
if not output.is_relative_to((root / 'artifacts').resolve()) or output.exists():
    parser.error('--output must be a fresh directory within this checkout artifacts; preserve previous evidence.')

def read_ini(path):
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.lstrip().startswith(('#', ';')):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    return values

hardware_file = avd_dir / 'hardware-qemu.ini'
if not hardware_file.is_file():
    parser.error('The owned AVD must have generated hardware-qemu.ini from its current launch.')
try:
    hardware = read_ini(hardware_file)
except (OSError, UnicodeError):
    parser.error('Generated hardware configuration is not a readable UTF-8 INI file.')
hardware_digest = hashlib.sha256(hardware_file.read_bytes()).hexdigest()
if hardware.get('avd.name') != f'wa-reco-api{args.sdk}':
    parser.error('Generated hardware configuration belongs to another AVD.')
if any(hardware.get(key, '').lower() not in ('no', 'false', '0')
       for key in ('hw.audioInput', 'hw.audioOutput')):
    parser.error('Actual generated hardware must disable host audio input AND output before capture.')
data_image = Path(hardware.get('disk.dataPartition.path', '')).resolve()
if not data_image.is_relative_to(avd_dir) or not data_image.is_file():
    parser.error('Generated userdata image must be an existing file inside the exact owned AVD.')
apk_digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
if args.expected_apk_sha256 and apk_digest != args.expected_apk_sha256.lower():
    parser.error('APK does not match the explicitly supplied expected SHA256.')
jar_digest = hashlib.sha256(args.jar.read_bytes()).hexdigest()
build_manifest_path = args.jar.resolve().parent / 'build-manifest.json'
build_manifest = None
build_manifest_digest = None
if build_manifest_path.is_file():
    manifest_bytes = build_manifest_path.read_bytes()
    try:
        build_manifest = json.loads(manifest_bytes)
    except (ValueError, UnicodeError):
        parser.error('Probe build-manifest must be valid JSON.')
    if not isinstance(build_manifest, dict) or not isinstance(build_manifest.get('output'), dict):
        parser.error('Probe build-manifest must contain its output object.')
    if build_manifest['output'].get('sha256') != jar_digest:
        parser.error('Probe JAR differs from its recorded build-manifest output digest.')
    build_manifest_digest = hashlib.sha256(manifest_bytes).hexdigest()

def adb(*command, timeout=30, binary=False):
    return subprocess.run(['adb', '-s', args.serial, *command], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout,
                          text=not binary, encoding=None if binary else 'utf-8', errors=None if binary else 'replace')

deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    result = adb('shell', 'getprop', 'sys.boot_completed', timeout=10)
    if result.returncode == 0 and result.stdout.strip() == '1':
        break
    time.sleep(2)
else:
    raise SystemExit('Owned emulator failed to boot within180s; no capture/app result asserted.')

avd_name = adb('emu', 'avd', 'name')
name_lines = avd_name.stdout.replace('\r', '').splitlines()
name = name_lines[0] if name_lines else ''
if avd_name.returncode != 0 or name != f'wa-reco-api{args.sdk}':
    raise SystemExit(f'Unexpected AVD {name!r}; preserve unrelated devices.')
props = {}
for key in ['ro.build.version.sdk', 'ro.build.version.release', 'ro.build.fingerprint',
            'ro.product.model', 'ro.product.manufacturer', 'ro.product.cpu.abilist', 'ro.hardware',
            'ro.build.type', 'ro.debuggable']:
    result = adb('shell', 'getprop', key)
    if result.returncode != 0:
        raise SystemExit(f'Could not verify device property {key}; no capture authorized.')
    props[key] = result.stdout.strip()
if props['ro.build.version.sdk'] != str(args.sdk) or props['ro.hardware'] not in ('ranchu', 'goldfish'):
    raise SystemExit('Device SDK/hardware does not match the owned emulator; no capture authorized.')
if props['ro.build.type'] not in ('user', 'userdebug', 'eng') or props['ro.debuggable'] not in ('0', '1'):
    raise SystemExit('Could not establish actual build type/debuggable state; no capture authorized.')
installed = adb('shell', 'pm', 'list', 'packages', '-3')
if installed.returncode != 0 or installed.stdout.strip():
    raise SystemExit('Probe requires an empty owned emulator with no third-party packages; preserve installed data.')
if hashlib.sha256(hardware_file.read_bytes()).hexdigest() != hardware_digest:
    raise SystemExit('Owned emulator hardware configuration changed during validation; no capture authorized.')
output.mkdir(parents=True)
args.output = output
receipt = {'schema': 2, 'avd': name, 'environment': 'owned_empty_emulator', 'properties': props,
           'avdDirectory': str(avd_dir), 'hardwareConfigurationSha256': hardware_digest,
           'thirdPartyPackagesCheckedEmpty': True,
           'jarSha256': jar_digest, 'probeBuildManifestVerified': build_manifest is not None,
           'probeBuildManifestSha256': build_manifest_digest,
           'probeSourceGitHead': build_manifest.get('gitHead') if build_manifest else None,
           'apkSha256': apk_digest, 'expectedApkSha256': args.expected_apk_sha256.lower() if args.expected_apk_sha256 else None,
           'apkDigestVerifiedAgainstExpected': bool(args.expected_apk_sha256),
           'realWhatsAppCallTested': False, 'physicalOemTested': False,
           'hostAudioInputDisabled': True, 'hostAudioOutputDisabled': True, 'fullPairingUiTested': False,
           'automaticCallDetectionTested': False, 'fullHelperLifecycleTested': False,
           'normalOemUserBuildTested': False}
remote = '/data/local/tmp/wa-reco-runtime-audio-probe.jar'
push = adb('push', str(args.jar.resolve()), remote)
if push.returncode != 0:
    raise SystemExit('Probe push failed; no runtime result asserted.')
probe = adb('shell', f'CLASSPATH={remote} /system/bin/app_process / com.codaki.usbaudio.RuntimeAudioProbe', timeout=40)
(args.output / 'runtime-probe.stdout.log').write_text(probe.stdout, encoding='utf-8')
(args.output / 'runtime-probe.stderr.log').write_text(probe.stderr, encoding='utf-8')
marker = 'WA_RUNTIME_AUDIO_PROBE '
lines = [line[len(marker):] for line in probe.stdout.splitlines() if line.startswith(marker)]
if probe.returncode != 0 or len(lines) != 1:
    raise SystemExit('Missing/ambiguous actual runtime result.')
try:
    receipt['captureProbe'] = json.loads(lines[0])
except ValueError:
    raise SystemExit('Runtime probe returned malformed JSON; no capture result asserted.')
if not isinstance(receipt['captureProbe'], dict):
    raise SystemExit('Runtime probe returned an invalid result object.')
if receipt['captureProbe'].get('sdk') != args.sdk or receipt['captureProbe'].get('uid') != 2000:
    raise SystemExit('Runtime result SDK/UID differs from the authorized emulator.')
install = adb('install', str(args.apk.resolve()), timeout=90)
receipt['releaseInstallation'] = {'exitCode': install.returncode, 'stdout': install.stdout.strip(),
                                  'stderr': install.stderr.strip()}
if install.returncode == 0:
    component = 'com.didi4164.WhatsAppCallRecorder.standalone/com.didi4164.WhatsAppCallRecorder.MainActivity'
    launch = adb('shell', 'am', 'start', '-W', '-n', component, timeout=40)
    time.sleep(4)
    pid = adb('shell', 'pidof', 'com.didi4164.WhatsAppCallRecorder.standalone')
    receipt['releaseLaunch'] = {'exitCode': launch.returncode, 'result': launch.stdout.strip(),
                                'processPresentAfter4s': bool(pid.stdout.strip())}
    screenshot = adb('exec-out', 'screencap', '-p', binary=True)
    if screenshot.returncode == 0 and screenshot.stdout.startswith(b'\x89PNG'):
        (args.output / 'release-launch.png').write_bytes(screenshot.stdout)
else:
    receipt['releaseLaunch'] = {'status': 'not_run_installation_failed'}
(args.output / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'sdk': args.sdk, 'captureStatus': receipt['captureProbe']['status'],
                  'grants': receipt['captureProbe'].get('shellGrants'),
                  'releaseInstallExitCode': install.returncode,
                  'physicalWhatsAppValidation': False, 'resultPath': str(args.output / 'result.json')}))
