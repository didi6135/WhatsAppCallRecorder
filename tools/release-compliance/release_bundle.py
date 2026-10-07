#!/usr/bin/env python3
"""Prepare pinned library source and a mechanically checked APK companion bundle.

This records evidence and outstanding review gates; it is not a legal certification.
Only explicitly supplied artifacts and notice text enter the bundle.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import re
import stat
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
JAVA_COMMIT = '7615ddd680b990e14513ebb66eac4cb0dbf82464'
NATIVE_COMMIT = '0d15933e5ba3e662cb01245a7ac0dc9fca3eac31'
BORINGSSL_COMMIT = '49f0329110a1d93a5febc2bceceedc655d995420'
FONT_HASH = 'ef149f08bdd2ff09a4e2c8573476b7b0f3fbb15b623954ade59899e7175bedda'
SPAKE_AAR_HASH = '8798fb6c04b5d53a6307ed9481e9afe563227abd4e8b9e917c8514fa850071bb'
CONSCRYPT_AAR_HASH = '551ae4e301c571760d1791e647db6ed1dcb10d34dcae7aa12b67f220f2ce98d1'
ARCHIVES = (
    ('spake2-java', JAVA_COMMIT, '7c8cf3220d9b528a21093b4cc78bd2b4616fba3c3209877d7194aa9e6c149434'),
    ('spake2-c', NATIVE_COMMIT, '0f1ae5da428e4f8ddea49e0e33dcfe07d2ac2114443793b8508bdc4dd48e1eec'),
)
MAX_ARCHIVE = 512 * 1024 * 1024
MAX_ENTRY = 256 * 1024 * 1024
MAX_STRIP_TOOL = 128 * 1024 * 1024
MAX_EXPANDED = 2 * 1024 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def load_json(path):
    data = Path(path).read_bytes()
    if len(data) > 8 * 1024 * 1024:
        raise ValueError('JSON input exceeds bounds')
    return json.loads(data)


def checked_zip(data):
    if len(data) > MAX_ARCHIVE:
        raise ValueError('ZIP exceeds compressed size bound')
    archive = zipfile.ZipFile(io.BytesIO(data))
    names = set()
    total = 0
    for item in archive.infolist():
        # On Windows ZipInfo normalizes backslashes and truncates NULs while
        # reading. Validate the original header string before that normalization.
        name = item.orig_filename
        path = PurePosixPath(name)
        if (not name or path.is_absolute() or '..' in path.parts or '\\' in name
                or ':' in name or any(ord(c) < 32 for c in name) or name in names
                or name != item.filename):
            raise ValueError('Unsafe or duplicate ZIP entry')
        names.add(name)
        mode = item.external_attr >> 16
        if stat.S_ISLNK(mode) or item.flag_bits & 1:
            raise ValueError('Links/encrypted ZIP entries are unsupported')
        total += item.file_size
        if item.file_size > MAX_ENTRY or total > MAX_EXPANDED or len(names) > 30000:
            raise ValueError('ZIP expanded size/count exceeds bounds')
    return archive


def write_zip(path, files):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'x') as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (0o100755 if name.endswith('/gradlew') else 0o100644) << 16
            archive.writestr(info, data)


def fetch(url, expected):
    request = urllib.request.Request(url, headers={'User-Agent': 'wa-reco-source-bundle/1'})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024 or digest(data) != expected:
        raise ValueError('Pinned upstream archive hash mismatch or oversized response')
    return data


def prepare_sources(output, downloader=fetch):
    files = {}
    for repo, commit, expected in ARCHIVES:
        url = f'https://codeload.github.com/MuntashirAkon/{repo}/zip/{commit}'
        archive = checked_zip(downloader(url, expected))
        prefix = f'{repo}-{commit}/'
        target = 'spake2/' if repo == 'spake2-java' else 'spake2/android/src/main/cpp/spake2-c/'
        for item in archive.infolist():
            if item.is_dir():
                continue
            if not item.filename.startswith(prefix):
                raise ValueError('Upstream archive root mismatch')
            name = target + item.filename[len(prefix):]
            if name in files:
                raise ValueError('Overlapping upstream source entry')
            files[name] = archive.read(item)
    for name in ('spake2/LICENSE', 'spake2/android/src/main/cpp/spake2-c/LICENSE',
                 'spake2/android/src/main/cpp/spake2-c/spake2.c',
                 'spake2/android/src/main/cpp/spake2-c/sha512.c',
                 'spake2/android/src/main/cpp/spake2_jni.cpp', 'spake2/gradlew'):
        if name not in files:
            raise ValueError('Required library build source is missing')
    manifest = {'schema': 1, 'javaCommit': JAVA_COMMIT, 'nativeCommit': NATIVE_COMMIT,
                'archives': [{'repository': r, 'commit': c, 'sha256': h} for r, c, h in ARCHIVES],
                'files': [{'path': n, 'sha256': digest(d), 'bytes': len(d)} for n, d in sorted(files.items())]}
    files['SPAKE2_SOURCE_MANIFEST.json'] = json_bytes(manifest)
    for name in ('docs/apk-redistribution.md', 'docs/rebuild-spake2.md',
                 'tools/release-compliance/recombine.init.gradle',
                 'LICENSES/LGPL-3.0.txt', 'LICENSES/GPL-3.0.txt',
                 'LICENSES/third-party/SPAKE2-LGPL-2.1.txt'):
        files[name] = (ROOT / name).read_bytes()
    write_zip(output, files)
    return {'sha256': digest(Path(output).read_bytes()), 'sourceFiles': len(manifest['files'])}


def exporter():
    spec = importlib.util.spec_from_file_location('public_exporter', ROOT / 'tools/export-public-source.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_app_source(data):
    archive = checked_zip(data)
    prefix = 'WhatsAppCallRecorder/'
    manifest = json.loads(archive.read(prefix + 'SOURCE_MANIFEST.json'))
    if not re.fullmatch(r'[0-9a-f]{40}', manifest.get('sourceCommit', '')):
        raise ValueError('Invalid app source commit')
    entries = manifest['files']
    if manifest['fileCount'] != len(entries) or len({e['path'] for e in entries}) != len(entries):
        raise ValueError('Invalid app source manifest count')
    if set(archive.namelist()) != {prefix + e['path'] for e in entries} | {prefix + 'SOURCE_MANIFEST.json'}:
        raise ValueError('App source has unmanifested files')
    policy = exporter()
    for entry in entries:
        name = entry['path']
        content = archive.read(prefix + name)
        if (not policy.included(name) or policy.findings(name, content)
                or len(content) != entry['bytes'] or digest(content) != entry['sha256']):
            raise ValueError('App source privacy/hash check failed')
        if name == 'android/app/debug.keystore' and digest(content) != '221e0a3106aa4c3ccc154e0a418b55020b3f9ea6e84f92e8749cd9e2f39f5e58':
            raise ValueError('Unexpected debug key')
    if not {'LICENSE', 'THIRD_PARTY_NOTICES.md', 'android/app/build.gradle', 'package-lock.json',
            'docs/rebuild-spake2.md'}.issubset({e['path'] for e in entries}):
        raise ValueError('App rebuild/license inputs missing')
    if (archive.getinfo(prefix + 'android/gradlew').external_attr >> 16) & 0o111 == 0:
        raise ValueError('App Gradle wrapper lost executable mode')
    return manifest['sourceCommit']


def verify_spake_source(data):
    archive = checked_zip(data)
    manifest = json.loads(archive.read('SPAKE2_SOURCE_MANIFEST.json'))
    if manifest.get('javaCommit') != JAVA_COMMIT or manifest.get('nativeCommit') != NATIVE_COMMIT:
        raise ValueError('Library source pin mismatch')
    # Reconstruct the pinned upstream content, not just a caller-authored manifest.
    expected = {}
    for repo, commit, sha in ARCHIVES:
        upstream = checked_zip(fetch(f'https://codeload.github.com/MuntashirAkon/{repo}/zip/{commit}', sha))
        prefix = f'{repo}-{commit}/'
        target = 'spake2/' if repo == 'spake2-java' else 'spake2/android/src/main/cpp/spake2-c/'
        for item in upstream.infolist():
            if not item.is_dir():
                expected[target + item.filename[len(prefix):]] = upstream.read(item)
    if {e['path'] for e in manifest['files']} != set(expected):
        raise ValueError('Incomplete corresponding library source')
    extra = {'SPAKE2_SOURCE_MANIFEST.json', 'docs/apk-redistribution.md', 'docs/rebuild-spake2.md',
             'tools/release-compliance/recombine.init.gradle', 'LICENSES/LGPL-3.0.txt',
             'LICENSES/GPL-3.0.txt', 'LICENSES/third-party/SPAKE2-LGPL-2.1.txt'}
    if set(archive.namelist()) != set(expected) | extra:
        raise ValueError('Unexpected corresponding-source package file')
    for entry in manifest['files']:
        content = archive.read(entry['path'])
        if content != expected[entry['path']] or len(content) != entry['bytes'] or digest(content) != entry['sha256']:
            raise ValueError('Library source content differs from exact upstream')
    return manifest


def package_modified_source(directory, output):
    directory = Path(directory).resolve()
    files = {}
    # Select only upstream source/build paths; never include a working build tree or keys.
    for repo, commit, sha in ARCHIVES:
        archive = checked_zip(fetch(f'https://codeload.github.com/MuntashirAkon/{repo}/zip/{commit}', sha))
        prefix = f'{repo}-{commit}/'
        target = '' if repo == 'spake2-java' else 'android/src/main/cpp/spake2-c/'
        for item in archive.infolist():
            if item.is_dir():
                continue
            relative = target + item.filename[len(prefix):]
            local = (directory / relative).resolve()
            if not local.is_relative_to(directory) or not local.is_file() or local.stat().st_size > MAX_ENTRY:
                raise ValueError('Modified source input missing or unsafe')
            data = local.read_bytes()
            if relative.endswith('/LICENSE') or relative == 'LICENSE':
                if data != archive.read(item):
                    raise ValueError('Retain the original upstream license texts')
            if exporter().findings(relative, data):
                raise ValueError('Modified source privacy check failed')
            files['spake2/' + relative] = data
    write_zip(output, files)
    return {'sha256': digest(Path(output).read_bytes()), 'sourceFiles': len(files)}


def strip_tool_hash(path):
    # Read only the explicitly supplied executable; never export its local path.
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_STRIP_TOOL:
        raise ValueError('llvm-strip executable missing or exceeds bounds')
    result = hashlib.sha256()
    total = 0
    with path.open('rb') as source:
        while block := source.read(1024 * 1024):
            total += len(block)
            if total > MAX_STRIP_TOOL:
                raise ValueError('llvm-strip executable exceeds bounds')
            result.update(block)
    if not total:
        raise ValueError('llvm-strip executable is empty')
    return result.hexdigest()


def stripped_match(original, packaged, strip_tool):
    if not 0 < len(original) <= MAX_ENTRY or not 0 < len(packaged) <= MAX_ENTRY:
        raise ValueError('Native stripping input exceeds bounds')
    try:
        tool = Path(strip_tool).resolve(strict=True)
        if tool.name.lower() not in {'llvm-strip', 'llvm-strip.exe'}:
            raise ValueError('Supply the existing llvm-strip executable')
        tool_hash = strip_tool_hash(tool)
        with tempfile.TemporaryDirectory(prefix='wa-reco-native-strip-') as directory:
            root = Path(directory)
            source = root / 'input.so'
            # Require two independent transformations to reproduce the exact APK
            # bytes. No metadata-only, size-only or operator-asserted equivalence.
            for attempt in range(2):
                source.write_bytes(original)
                output = root / f'output-{attempt}.so'
                result = subprocess.run([str(tool), '--strip-unneeded', '-o', str(output), str(source)],
                                        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                        stderr=subprocess.DEVNULL, shell=False, timeout=30)
                if result.returncode != 0 or output.is_symlink() or not output.is_file():
                    raise ValueError('llvm-strip did not produce a regular native library')
                if not 0 < output.stat().st_size <= MAX_ENTRY:
                    raise ValueError('llvm-strip output exceeds bounds')
                if output.read_bytes() != packaged:
                    raise ValueError('Stripped native library differs from APK')
            if strip_tool_hash(tool) != tool_hash:
                raise ValueError('llvm-strip executable changed during verification')
        return {'kind': 'llvm-strip', 'toolSha256': tool_hash, 'arguments': ['--strip-unneeded']}
    except (OSError, subprocess.TimeoutExpired):
        # Tool errors may contain private SDK/temp paths; retain only a fixed error.
        raise ValueError('llvm-strip execution failed or timed out') from None


def native_matches(apk, aar, library, strip_tool=None):
    packaged = {n: apk.read(n) for n in apk.namelist() if re.fullmatch(r'lib/[^/]+/' + re.escape(library), n)}
    if not packaged:
        raise ValueError('APK lacks required native library')
    rows = []
    for name, data in sorted(packaged.items()):
        source = 'jni/' + name[len('lib/'):]
        if source not in aar.namelist():
            raise ValueError('APK native library differs from resolved AAR')
        original = aar.read(source)
        if data == original:
            transformation = {'kind': 'identity'}
        elif strip_tool is not None:
            transformation = stripped_match(original, data, strip_tool)
        else:
            raise ValueError('APK native library differs from resolved AAR')
        rows.append({'path': name, 'sha256': digest(data), 'originalSha256': digest(original),
                     'transformation': transformation})
    return rows


def record_recombination(args):
    release = Path(args.apk).read_bytes()
    source = Path(args.app_source).read_bytes()
    commit = verify_app_source(source)
    modified_apk = Path(args.modified_apk).read_bytes()
    modified_aar = Path(args.modified_aar).read_bytes()
    originals = checked_zip(release)
    binaries = native_matches(checked_zip(modified_apk), checked_zip(modified_aar), 'libspake2.so')
    if all(originals.read(row['path']) == checked_zip(modified_apk).read(row['path']) for row in binaries):
        raise ValueError('Recombination must demonstrate a changed library, not reuse the original binary')
    changed_source = Path(args.modified_source).read_bytes()
    changed = checked_zip(changed_source)
    expected = {}
    for repo, revision, sha in ARCHIVES:
        original = checked_zip(fetch(f'https://codeload.github.com/MuntashirAkon/{repo}/zip/{revision}', sha))
        prefix = f'{repo}-{revision}/'
        target = 'spake2/' if repo == 'spake2-java' else 'spake2/android/src/main/cpp/spake2-c/'
        for item in original.infolist():
            if not item.is_dir():
                expected[target + item.filename[len(prefix):]] = original.read(item)
    if set(changed.namelist()) != set(expected):
        raise ValueError('Modified source package must contain complete source/build inputs')
    if not any(changed.read(n) != d for n, d in expected.items() if n.endswith(('.c', '.cpp', '.h'))):
        raise ValueError('Modified native source must demonstrate a source change')
    result = {'schema': 1, 'releaseApkSha256': digest(release), 'appSourceSha256': digest(source),
              'appSourceCommit': commit, 'modifiedApkSha256': digest(modified_apk),
              'modifiedAarSha256': digest(modified_aar), 'modifiedSourceSha256': digest(changed_source),
              'modifiedNativeLibraries': binaries,
              'operatorChecks': {'ownKeySigned': args.own_key_signed,
                                 'installationInstructionsReviewed': args.installation_instructions_reviewed}}
    with Path(args.output).open('xb') as output:
        output.write(json_bytes(result))
    return result


def make_bundle(args):
    apk_data = Path(args.apk).read_bytes()
    app_data = Path(args.app_source).read_bytes()
    spake_data = Path(args.spake_source).read_bytes()
    commit = verify_app_source(app_data)
    verify_spake_source(spake_data)
    apk = checked_zip(apk_data)
    spake = Path(args.spake_aar).read_bytes()
    conscrypt = Path(args.conscrypt_aar).read_bytes()
    if digest(spake) != SPAKE_AAR_HASH or digest(conscrypt) != CONSCRYPT_AAR_HASH:
        raise ValueError('Resolved pinned AAR hash mismatch; review any dependency change')
    strip_tool = getattr(args, 'strip_tool', None)
    libraries = native_matches(apk, checked_zip(spake), 'libspake2.so', strip_tool)
    libraries += native_matches(apk, checked_zip(conscrypt), 'libconscrypt_jni.so', strip_tool)
    props = checked_zip(checked_zip(conscrypt).read('classes.jar')).read('org/conscrypt/conscrypt.properties')
    if f'org.conscrypt.boringssl.version={BORINGSSL_COMMIT}'.encode() not in props.splitlines():
        raise ValueError('Conscrypt BoringSSL pin mismatch')
    fonts = [n for n in apk.namelist() if n.endswith('/MaterialIcons.ttf')]
    if len(fonts) != 1 or digest(apk.read(fonts[0])) != FONT_HASH:
        raise ValueError('Packaged MaterialIcons font differs from attributed upstream font')
    inventory = load_json(args.inventory)
    if inventory.get('schema') != 1 or inventory.get('appSourceCommit') != commit:
        raise ValueError('Inventory is not tied to this app source commit')
    files = {'application.apk': apk_data, 'application-source.zip': app_data,
             'spake2-corresponding-source.zip': spake_data}
    # Inventory is deliberately small/public; private Gradle cache paths cannot be exported.
    expected_keys = {'schema', 'appSourceCommit', 'apkSha256', 'components', 'reviewed'}
    if set(inventory) != expected_keys or inventory['apkSha256'] != digest(apk_data):
        raise ValueError('Inventory shape/APK binding is invalid')
    if not isinstance(inventory['components'], list) or not inventory['components']:
        raise ValueError('Resolved component inventory is empty')
    identifiers = set()
    all_reviewed = inventory['reviewed'] is True
    notice_root = Path(args.notices).resolve()
    for component in inventory['components']:
        if set(component) != {'id', 'sha256', 'license', 'noticeFiles', 'reviewed'}:
            raise ValueError('Component inventory has unsupported fields')
        identifier = component['id']
        if (not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9@_./:+-]{1,240}', identifier)
                or identifier in identifiers or not re.fullmatch('[0-9a-f]{64}', component['sha256'])):
            raise ValueError('Invalid or duplicate component identity/hash')
        if not isinstance(component['license'], str) or not 1 <= len(component['license']) <= 250:
            raise ValueError('Component license description missing or oversized')
        if exporter().findings('inventory.json', json_bytes(component)):
            raise ValueError('Component inventory privacy check failed')
        expected_hash = {
            'com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1': SPAKE_AAR_HASH,
            'org.conscrypt:conscrypt-android:2.5.3': CONSCRYPT_AAR_HASH,
            'npm:react-native-vector-icons@10.2.0': FONT_HASH,
        }.get(identifier)
        if expected_hash and component['sha256'] != expected_hash:
            raise ValueError('Required component artifact hash mismatch')
        identifiers.add(identifier)
        all_reviewed = all_reviewed and component['reviewed'] is True and bool(component['noticeFiles'])
        for relative in component['noticeFiles']:
            path = PurePosixPath(relative)
            if path.is_absolute() or '..' in path.parts or '\\' in relative or path.suffix.lower() not in {'.txt', '.md'}:
                raise ValueError('Unsafe notice file')
            local = (notice_root / relative).resolve()
            if not local.is_relative_to(notice_root) or not local.is_file() or local.stat().st_size > 1024 * 1024:
                raise ValueError('Notice file missing or exceeds bounds')
            content = local.read_bytes()
            content.decode('utf-8')
            if exporter().findings(relative, content):
                raise ValueError('Notice text privacy check failed')
            files['notices/resolved/' + relative] = content
    required_ids = {'com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1',
                    'org.conscrypt:conscrypt-android:2.5.3', 'npm:react-native-vector-icons@10.2.0'}
    if not required_ids.issubset(identifiers):
        raise ValueError('Required runtime components absent from inventory')
    files['RESOLVED_COMPONENTS.json'] = json_bytes(inventory)
    for folder in ('LICENSES', 'android/vendor/libadb/LICENSES'):
        for local in (ROOT / folder).rglob('*'):
            if local.is_file():
                files['notices/' + local.relative_to(ROOT).as_posix()] = local.read_bytes()
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md', 'docs/apk-redistribution.md', 'docs/rebuild-spake2.md',
                 'tools/release-compliance/notice-origins.json', 'tools/release-compliance/recombine.init.gradle'):
        files[name] = (ROOT / name).read_bytes()
    recombination_checked = False
    if args.recombination:
        receipt = load_json(args.recombination)
        checks = receipt.get('operatorChecks', {})
        recombination_checked = (receipt.get('schema') == 1 and receipt.get('releaseApkSha256') == digest(apk_data)
                                 and receipt.get('appSourceSha256') == digest(app_data)
                                 and receipt.get('appSourceCommit') == commit
                                 and checks.get('ownKeySigned') is True
                                 and checks.get('installationInstructionsReviewed') is True
                                 and bool(receipt.get('modifiedNativeLibraries')))
        if not recombination_checked:
            raise ValueError('Recombination receipt is stale or incomplete')
        files['RECOMBINATION_CHECK.json'] = json_bytes(receipt)
    gates = []
    if not all_reviewed:
        gates.append('Resolved runtime license/notice review incomplete')
    if not recombination_checked:
        gates.append('Modified-library rebuild/recombination and installation review not recorded')
    if args.require_ready and gates:
        raise ValueError('; '.join(gates))
    manifest = {'schema': 1, 'appSourceCommit': commit, 'apkSha256': digest(apk_data),
                'mechanicalChecksPassed': True, 'operatorEvidenceComplete': not gates,
                'notLegalCertification': True, 'outstandingGates': gates,
                'packagedLibraries': libraries,
                'files': [{'path': n, 'bytes': len(d), 'sha256': digest(d)} for n, d in sorted(files.items())]}
    files['RELEASE_BUNDLE_MANIFEST.json'] = json_bytes(manifest)
    write_zip(args.output, files)
    return {'outputSha256': digest(Path(args.output).read_bytes()), 'appSourceCommit': commit,
            'operatorEvidenceComplete': not gates, 'outstandingGates': gates}


def check_bundle(path):
    archive = checked_zip(Path(path).read_bytes())
    manifest = json.loads(archive.read('RELEASE_BUNDLE_MANIFEST.json'))
    rows = manifest['files']
    if len({r['path'] for r in rows}) != len(rows) or set(archive.namelist()) != {r['path'] for r in rows} | {'RELEASE_BUNDLE_MANIFEST.json'}:
        raise ValueError('Bundle manifest entry mismatch')
    for row in rows:
        data = archive.read(row['path'])
        if len(data) != row['bytes'] or digest(data) != row['sha256']:
            raise ValueError('Bundle file hash/size mismatch')
    return {'hashesVerified': len(rows), 'operatorEvidenceComplete': manifest['operatorEvidenceComplete'],
            'outstandingGates': manifest['outstandingGates'], 'notLegalCertification': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    source = commands.add_parser('sources')
    source.add_argument('--output', required=True)
    modified = commands.add_parser('modified-sources')
    modified.add_argument('--directory', required=True)
    modified.add_argument('--output', required=True)
    bundle = commands.add_parser('bundle')
    for flag in ('apk', 'app-source', 'spake-source', 'spake-aar', 'conscrypt-aar', 'inventory', 'notices', 'output'):
        bundle.add_argument('--' + flag, required=True)
    bundle.add_argument('--recombination')
    bundle.add_argument('--strip-tool', help='Existing llvm-strip executable for exact native stripping proof')
    bundle.add_argument('--require-ready', action='store_true')
    recombine = commands.add_parser('record-recombination')
    for flag in ('apk', 'app-source', 'modified-apk', 'modified-aar', 'modified-source', 'output'):
        recombine.add_argument('--' + flag, required=True)
    recombine.add_argument('--own-key-signed', action='store_true')
    recombine.add_argument('--installation-instructions-reviewed', action='store_true')
    check = commands.add_parser('check')
    check.add_argument('bundle')
    args = parser.parse_args()
    if args.command == 'sources':
        result = prepare_sources(args.output)
    elif args.command == 'modified-sources':
        result = package_modified_source(args.directory, args.output)
    elif args.command == 'bundle':
        result = make_bundle(args)
    elif args.command == 'record-recombination':
        result = record_recombination(args)
    else:
        result = check_bundle(args.bundle)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
