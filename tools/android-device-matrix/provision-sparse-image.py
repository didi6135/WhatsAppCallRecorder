"""Fetch a verified official test image; preserve zero-filled extents on Windows.

The ordinary SDK installer exhausted this host's disk while expanding Android35.
The downloaded ZIP stays in RAM; output files retain identical logical bytes.
Optional Windows developer tooling, not an application/user installation step.
Requires fsutil and network access; no existing SDK image/profile/file is
overwritten or deleted. Images are AOSP test builds, not physical OEM evidence.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', type=int, choices=[33, 34, 35, 36], required=True)
parser.add_argument('--sdk-root', type=Path,
                    help='Fresh target package under a checkout artifacts SDK root; preserves failed attempts.')
args = parser.parse_args()
if sys.platform != 'win32' or not shutil.which('fsutil'):
    parser.error('This optional sparse extraction requires Windows with installed fsutil.')
root = Path(__file__).resolve().parents[2]
requested_sdk_root = args.sdk_root or root / 'artifacts/sparse-test-sdk'
sdk_root = requested_sdk_root.resolve()
artifacts_root = root / 'artifacts'
if sdk_root != requested_sdk_root.absolute() or artifacts_root.resolve() != artifacts_root.absolute():
    parser.error('--sdk-root and checkout artifacts must not redirect through links or path aliases.')
if sdk_root == artifacts_root or not sdk_root.is_relative_to(artifacts_root):
    parser.error('--sdk-root must be a directory strictly within this checkout artifacts.')
target = sdk_root / f'system-images/android-{args.sdk}/default/x86_64'
proof_path = root / f'artifacts/android-device-matrix/image-api{args.sdk}.json'
if target.exists() or proof_path.exists():
    parser.error('Preserve existing images/evidence; choose a fresh bounded --sdk-root or checkout.')
# Failed extraction leaves partial files, never a verified image. Use a different
# bounded --sdk-root to retry without deleting the original attempt. Completion
# requires every logical image byte and the full checksum proof below.
if target.resolve() != target.absolute() or proof_path.resolve() != proof_path.absolute():
    parser.error('Owned image/evidence paths must not redirect through symlinks.')
if shutil.disk_usage(root).free <= 2 * 1024**3:
    parser.error('Insufficient safe free space for optional test-image provisioning.')

def require(condition, message):
    if not condition:
        raise ValueError(message)
url = 'https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml'
catalog = urllib.request.urlopen(url, timeout=40).read()
tree = ET.fromstring(catalog)
package_path = f'system-images;android-{args.sdk};default;x86_64'
packages = [node for node in tree.iter() if node.tag.split('}')[-1] == 'remotePackage' and node.attrib.get('path') == package_path]
require(len(packages) == 1, 'Catalog must contain exactly one requested AOSP/x86_64 package.')
package = packages[0]
def descendant(node, name):
    matches = [item for item in node.iter() if item.tag.split('}')[-1] == name]
    require(bool(matches), f'Catalog is missing {name}.')
    return matches[0]
archive = descendant(package, 'archive')
complete = descendant(archive, 'complete')
archive_name = descendant(complete, 'url').text.strip()
require(bool(archive_name) and all(character not in archive_name for character in '/\\:')
        and archive_name not in ('.', '..'), 'Catalog archive must be a plain filename.')
download_url = url.rsplit('/', 1)[0] + '/' + archive_name
expected_size = int(descendant(complete, 'size').text)
checksum = descendant(complete, 'checksum')
expected_hash = checksum.text.strip().lower()
algorithm = checksum.attrib.get('type', 'sha1').lower().replace('-', '')
require(algorithm in ('sha1', 'sha256'), 'Unsupported archive checksum algorithm.')
require(0 < expected_size < 2 * 1024**3, 'Archive exceeds bounded in-memory download size.')
print(json.dumps({'stage': 'download', 'sdk': args.sdk, 'archiveBytes': expected_size}), flush=True)
payload = urllib.request.urlopen(download_url, timeout=60).read(expected_size + 1)
require(len(payload) == expected_size, 'Archive byte count differs from official catalog.')
require(hashlib.new(algorithm, payload).hexdigest() == expected_hash, 'Official archive checksum mismatch.')
records = []
target.mkdir(parents=True)
with zipfile.ZipFile(io.BytesIO(payload)) as zipped:
    for member in zipped.infolist():
        archive_path = PurePosixPath(member.filename.replace('\\', '/'))
        parts = archive_path.parts
        require(bool(parts) and parts[0] == 'x86_64' and '..' not in parts and not archive_path.is_absolute()
                and not any(':' in part or '\x00' in part for part in parts), 'Unsafe archive member path.')
        mode = member.external_attr >> 16
        require(not stat.S_ISLNK(mode), 'Archive symlinks are not accepted.')
        dest = target.joinpath(*parts[1:])
        require(dest.resolve().is_relative_to(target.resolve()), 'Archive output escapes owned image directory.')
        if member.is_dir():
            dest.mkdir(parents=True, exist_ok=True)
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        with dest.open('xb'):
            pass
        if dest.suffix == '.img':
            subprocess.run(['fsutil', 'sparse', 'setflag', str(dest)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        digest = hashlib.sha256()
        total = 0
        with zipped.open(member) as source, dest.open('r+b') as output:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                if dest.suffix == '.img' and not any(chunk):
                    output.seek(len(chunk), 1)
                else:
                    if shutil.disk_usage(root).free < len(chunk) + 64 * 1024**2:
                        raise OSError('Insufficient free space; stop without deleting or overwriting existing files.')
                    output.write(chunk)
                total += len(chunk)
            output.truncate(total)
        require(total == member.file_size and dest.stat().st_size == total, 'Extracted member logical size mismatch.')
        records.append({'path': str(dest.relative_to(sdk_root)).replace('\\', '/'),
                        'bytes': total, 'sha256': digest.hexdigest(), 'zipCrc32': member.CRC})
        print(json.dumps({'stage': 'extracted', 'file': member.filename, 'bytes': total}), flush=True)
props = {}
for line in (target / 'source.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith(('#', ';')):
        key, value = line.split('=', 1)
        props[key.strip()] = value.strip()
require(props.get('AndroidVersion.ApiLevel') == str(args.sdk), 'Extracted image API must match exactly.')
require(props.get('SystemImage.Abi') == 'x86_64' and props.get('SystemImage.TagId') == 'default',
        'Extracted image must be the requested actual AOSP/default x86_64 package.')
proof = {'package': package_path, 'catalogUrl': url, 'catalogSha256': hashlib.sha256(catalog).hexdigest(),
         'archiveUrl': download_url, 'archiveBytes': len(payload), 'archiveChecksumAlgorithm': algorithm,
         'archiveChecksum': expected_hash, 'archiveSha256': hashlib.sha256(payload).hexdigest(),
         'sparseLogicalBytesPreserved': True, 'files': records}
proof_path.parent.mkdir(parents=True, exist_ok=True)
with proof_path.open('x', encoding='utf-8') as output:
    output.write(json.dumps(proof, indent=2) + '\n')
print(json.dumps({'status': 'provisioned', 'sdk': args.sdk, 'files': len(records),
                  'freeBytes': shutil.disk_usage(root).free, 'proof': str(proof_path)}), flush=True)
