"""Optional Windows developer-only setup; never part of the app's user flow.

Requires installed Android emulator/qemu-img, fsutil and the selected WSL distro
with /sbin/mkfs.ext4. Creates only a fresh checkout-owned 2 GiB userdata image.
Existing SDK images, AVDs and installed applications are preserved.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', type=int, choices=[33, 34, 35, 36], required=True)
parser.add_argument('--image', type=Path, required=True)
parser.add_argument('--android-sdk', type=Path,
                    default=Path(os.environ.get('LOCALAPPDATA', str(Path.home() / 'AppData/Local'))) / 'Android/Sdk',
                    help='Existing SDK containing emulator/qemu-img.exe; no SDK download is performed.')
parser.add_argument('--wsl-distro', default='Ubuntu-22.04',
                    help='Existing WSL distribution providing /sbin/mkfs.ext4.')
args = parser.parse_args()
if sys.platform != 'win32':
    parser.error('This optional sparse-image setup requires Windows and WSL.')
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_. -]{0,79}', args.wsl_distro) or args.wsl_distro != args.wsl_distro.strip():
    parser.error('--wsl-distro must name one existing WSL distribution.')
if not shutil.which('fsutil') or not shutil.which('wsl'):
    parser.error('Installed fsutil and WSL are required; this tool does not install dependencies.')
emulator = args.android_sdk.resolve() / 'emulator/qemu-img.exe'
if not emulator.is_file():
    parser.error('--android-sdk must contain an existing emulator/qemu-img.exe.')
root = Path(__file__).resolve().parents[2]
if not re.fullmatch(r'[A-Za-z]:', root.drive):
    parser.error('WSL image formatting requires this checkout on a local Windows drive.')
home = root / 'artifacts/avd-home'
name = f'wa-reco-api{args.sdk}'
target = home / (name + '.avd')
profile = home / (name + '.ini')
if target.exists() or profile.exists():
    parser.error('Existing AVD directory or registration must be preserved.')
if target.resolve() != target.absolute() or profile.resolve() != profile.absolute():
    parser.error('Owned AVD paths must not redirect outside this checkout through symlinks.')
image = args.image.resolve()
if not (image / 'source.properties').is_file():
    parser.error('--image must contain source.properties.')
source_props = {}
for line in (image / 'source.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith(('#', ';')):
        key, value = line.split('=', 1)
        source_props[key.strip()] = value.strip()
if source_props.get('AndroidVersion.ApiLevel') != str(args.sdk):
    parser.error('Image API level must exactly match --sdk.')
if source_props.get('SystemImage.Abi') != 'x86_64':
    parser.error('This owned emulator setup requires the actual image ABI x86_64.')
tag = source_props.get('SystemImage.TagId', '')
if tag not in ('default', 'google_apis'):
    parser.error('Only actual AOSP/default or google_apis images are accepted for this test setup.')
if not all((image / file).is_file() for file in ('system.img', 'vendor.img', 'kernel-ranchu', 'ramdisk.img')):
    parser.error('Image must contain system/vendor/kernel/ramdisk files.')
home.mkdir(parents=True, exist_ok=True)
target.mkdir()
values = {'avd.ini.encoding': 'UTF-8', 'abi.type': 'x86_64', 'hw.cpu.arch': 'x86_64',
          'hw.cpu.ncore': '2', 'hw.ramSize': '1024', 'hw.lcd.width': '390',
          'hw.lcd.height': '844', 'hw.lcd.density': '160', 'hw.gpu.enabled': 'yes',
          'hw.gpu.mode': 'swiftshader_indirect', 'hw.keyboard': 'no',
          'hw.audioInput': 'no', 'hw.audioOutput': 'no', 'hw.camera.back': 'none',
          'hw.camera.front': 'none', 'hw.sdCard': 'no', 'hw.useext4': 'yes',
          'disk.cachePartition': 'yes', 'disk.cachePartition.size': '66MB',
          'userdata.useQcow2': 'yes', 'fastboot.forceColdBoot': 'yes',
          'fastboot.forceFastBoot': 'no', 'firstboot.saveToLocalSnapshot': 'no',
          'showDeviceFrame': 'no', 'PlayStore.enabled': 'no', 'vm.heapSize': '256M'}
data = target / 'userdata-ext4-2g.img'
with data.open('xb'):
    pass
subprocess.run(['fsutil', 'sparse', 'setflag', str(data)], check=True)
# qemu-img preserves sparse allocation; Windows _chsize_s may fill zero extents.
subprocess.run([str(emulator), 'resize', '-f', 'raw', str(data), '2G'], check=True)
# Format only this new owned sparse image; never a host disk or existing installation.
linux_image = '/mnt/' + data.drive[0].lower() + '/' + '/'.join(data.parts[1:])
subprocess.run(['wsl', '-d', args.wsl_distro, '--', '/sbin/mkfs.ext4', '-F', '-L', 'data', '-b', '4096',
                '-E', 'lazy_itable_init=1,lazy_journal_init=1', linux_image], check=True)
values.update({'image.sysdir.1': str(image).replace('\\', '/') + '/', 'avd.id': name, 'avd.name': name,
               'tag.id': tag, 'tag.ids': tag, 'tag.display': source_props.get('SystemImage.TagDisplay', tag),
               'tag.displaynames': source_props.get('SystemImage.TagDisplay', tag),
               'disk.dataPartition.path': str(data).replace('\\', '/'), 'disk.dataPartition.size': '2G'})
(target / 'config.ini').write_text(''.join(k + '=' + v + '\n' for k, v in values.items()), encoding='utf-8')
with profile.open('x', encoding='utf-8') as output:
    output.write('avd.ini.encoding=UTF-8\npath=' + str(target) + f'\ntarget=android-{args.sdk}\n')
print(f'Prepared fresh owned {name}; host audio/camera disabled; no other profile touched.')
