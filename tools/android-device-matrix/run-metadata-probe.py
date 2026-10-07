"""Explicit capability-only probe: no audio, calls, app install or settings changes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--jar', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_.:-]+', args.serial):
    parser.error('Unexpected device serial format.')
if args.output.exists():
    parser.error('Use a fresh output file to preserve prior evidence.')
jar_hash = hashlib.sha256(args.jar.read_bytes()).hexdigest()

def adb(*command):
    return subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True,
                          text=True, encoding='utf-8', errors='replace', timeout=40)

remote = '/data/local/tmp/wa-reco-metadata-' + jar_hash[:12] + '.jar'
if adb('push', str(args.jar.resolve()), remote).returncode != 0:
    raise SystemExit('Probe upload failed. Check the authorized ADB connection.')
result = adb('shell', f'CLASSPATH={remote} /system/bin/app_process / com.codaki.usbaudio.RuntimeAudioProbe --metadata-only')
marker = 'WA_RUNTIME_AUDIO_PROBE '
lines = [line[len(marker):] for line in result.stdout.splitlines() if line.startswith(marker)]
if result.returncode != 0 or len(lines) != 1:
    raise SystemExit('Missing or ambiguous capability-only result; no support result asserted.')
probe = json.loads(lines[0])
if not probe.get('metadataOnly') or probe.get('syntheticHz') is not None or any(key in probe for key in ('output', 'microphone')):
    raise SystemExit('Result does not match the capability-only contract.')
receipt = {'schema': 1, 'environment': 'device_metadata_only', 'jarSha256': jar_hash,
           'physicalAudioTested': False, 'realWhatsAppCallTested': False,
           'automaticCallDetectionTested': False, 'settingsChanged': False,
           'appInstalled': False, 'device': {}, 'probe': probe}
for key in ('ro.build.version.sdk', 'ro.build.version.release', 'ro.product.model',
            'ro.product.manufacturer', 'ro.product.cpu.abilist', 'ro.build.type', 'ro.debuggable'):
    prop = adb('shell', 'getprop', key)
    if prop.returncode != 0:
        raise SystemExit('Unable to read device capability metadata.')
    receipt['device'][key] = prop.stdout.strip()
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('x', encoding='utf-8') as output:
    output.write(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'sdk': probe.get('sdk'), 'status': probe.get('status'),
                  'audioTested': False, 'output': str(args.output)}))
