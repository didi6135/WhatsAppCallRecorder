import argparse
import importlib.util
import io
import json
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('release_bundle', Path(__file__).with_name('release_bundle.py'))
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)


def zip_bytes(files, modes=None):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name)
            info.external_attr = (modes or {}).get(name, 0o100644) << 16
            archive.writestr(info, data)
    return output.getvalue()


def app_source(extra=None, modes=None):
    files = {'LICENSE': b'0BSD', 'THIRD_PARTY_NOTICES.md': b'Notices',
             'android/app/build.gradle': b'build', 'package-lock.json': b'{}',
             'docs/rebuild-spake2.md': b'Rebuild', 'android/gradlew': b'wrapper'}
    files.update(extra or {})
    rows = [{'path': n, 'bytes': len(d), 'sha256': bundle.digest(d)} for n, d in files.items()]
    files['SOURCE_MANIFEST.json'] = bundle.json_bytes({'sourceCommit': 'a' * 40,
                                                     'fileCount': len(rows), 'files': rows})
    return zip_bytes({'WhatsAppCallRecorder/' + n: d for n, d in files.items()},
                     modes or {'WhatsAppCallRecorder/android/gradlew': 0o100755})


class ZipPrivacyTests(unittest.TestCase):
    def test_reject_traversal_absolute_backslash_drive_and_control(self):
        for name in ('../key', '/key', 'a/../../key', 'a\\key', 'C:/key', 'a\nkey'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                data = zip_bytes({name: b'x'})
                # Python's Windows ZipInfo normalizes backslashes on write; restore
                # the malicious raw entry name in both local/central ZIP headers.
                if '\\' in name:
                    data = data.replace(name.replace('\\', '/').encode(), name.encode())
                bundle.checked_zip(data)

    def test_reject_symlink(self):
        with self.assertRaises(ValueError):
            bundle.checked_zip(zip_bytes({'link': b'outside'}, {'link': stat.S_IFLNK | 0o777}))

    def test_reject_duplicate_entries(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('same', b'a')
            with self.assertWarns(UserWarning):
                archive.writestr('same', b'b')
        with self.assertRaises(ValueError):
            bundle.checked_zip(data.getvalue())

    def test_expansion_and_compressed_bounds(self):
        data = zip_bytes({'source': b'12345'})
        with patch.object(bundle, 'MAX_ENTRY', 4), self.assertRaises(ValueError):
            bundle.checked_zip(data)
        with patch.object(bundle, 'MAX_ARCHIVE', len(data) - 1), self.assertRaises(ValueError):
            bundle.checked_zip(data)

    def test_valid_application_source_commit_and_executable_wrapper(self):
        self.assertEqual('a' * 40, bundle.verify_app_source(app_source()))

    def test_private_sources_and_changed_debug_key_rejected(self):
        for name in ('.env', 'signing.properties', 'node_modules/pkg/LICENSE',
                     'android/app/release.jks', 'artifacts/call.wav', 'android/app/debug.keystore'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                bundle.verify_app_source(app_source({name: b'private'}))

    def test_wrapper_execute_mode_required(self):
        with self.assertRaises(ValueError):
            bundle.verify_app_source(app_source(modes={'WhatsAppCallRecorder/android/gradlew': 0o100644}))

    def test_unmanifested_and_tampered_application_source_rejected(self):
        original = bundle.checked_zip(app_source())
        files = {n: original.read(n) for n in original.namelist()}
        files['WhatsAppCallRecorder/LICENSE'] = b'tampered'
        with self.assertRaises(ValueError):
            bundle.verify_app_source(zip_bytes(files))
        files['WhatsAppCallRecorder/LICENSE'] = b'0BSD'
        files['WhatsAppCallRecorder/new.txt'] = b'unknown'
        with self.assertRaises(ValueError):
            bundle.verify_app_source(zip_bytes(files))

    def test_source_assembly_requires_native_submodule(self):
        upstream = zip_bytes({f'spake2-java-{bundle.JAVA_COMMIT}/LICENSE': b'license'})
        native = zip_bytes({f'spake2-c-{bundle.NATIVE_COMMIT}/LICENSE': b'license'})
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            bundle.prepare_sources(Path(directory) / 'source.zip',
                                   lambda url, expected: native if 'spake2-c/zip' in url else upstream)


class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.spake = zip_bytes({'jni/arm64-v8a/libspake2.so': b'spake'})
        properties = f'org.conscrypt.boringssl.version={bundle.BORINGSSL_COMMIT}\n'.encode()
        self.conscrypt = zip_bytes({'jni/arm64-v8a/libconscrypt_jni.so': b'conscrypt',
                                   'classes.jar': zip_bytes({'org/conscrypt/conscrypt.properties': properties})})
        self.apk_files = {'lib/arm64-v8a/libspake2.so': b'spake',
                          'lib/arm64-v8a/libconscrypt_jni.so': b'conscrypt',
                          'assets/fonts/MaterialIcons.ttf': b'font'}
        self.apk = zip_bytes(self.apk_files)
        self.write('app.apk', self.apk)
        self.write('app-source.zip', app_source())
        self.write('library-source.zip', b'synthetic source')
        self.write('spake.aar', self.spake)
        self.write('conscrypt.aar', self.conscrypt)
        self.write('notices/license.txt', b'License and retained copyright')
        ids = [('com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1', self.spake),
               ('org.conscrypt:conscrypt-android:2.5.3', self.conscrypt),
               ('npm:react-native-vector-icons@10.2.0', b'font')]
        self.inventory = {'schema': 1, 'appSourceCommit': 'a' * 40, 'apkSha256': bundle.digest(self.apk),
                          'reviewed': False, 'components': [
                              {'id': name, 'sha256': bundle.digest(data), 'license': 'retained terms',
                               'noticeFiles': ['license.txt'], 'reviewed': False} for name, data in ids]}
        self.save_inventory()
        self.args = argparse.Namespace(apk=str(self.root / 'app.apk'),
            app_source=str(self.root / 'app-source.zip'), spake_source=str(self.root / 'library-source.zip'),
            spake_aar=str(self.root / 'spake.aar'), conscrypt_aar=str(self.root / 'conscrypt.aar'),
            inventory=str(self.root / 'inventory.json'), notices=str(self.root / 'notices'),
            recombination=None, require_ready=False, output=str(self.root / 'companion.zip'))
        for key, value in [('SPAKE_AAR_HASH', bundle.digest(self.spake)),
                           ('CONSCRYPT_AAR_HASH', bundle.digest(self.conscrypt)),
                           ('FONT_HASH', bundle.digest(b'font'))]:
            patcher = patch.object(bundle, key, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(bundle, 'verify_spake_source', return_value={})
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def save_inventory(self):
        self.write('inventory.json', bundle.json_bytes(self.inventory))

    def test_review_bundle_reports_both_unfinished_gates_and_checks_hashes(self):
        result = bundle.make_bundle(self.args)
        self.assertFalse(result['operatorEvidenceComplete'])
        self.assertEqual(2, len(result['outstandingGates']))
        self.assertGreater(bundle.check_bundle(self.args.output)['hashesVerified'], 12)
        names = bundle.checked_zip(Path(self.args.output).read_bytes()).namelist()
        self.assertFalse(any('node_modules' in n or n.endswith('.aar') for n in names))

    def test_strict_release_rejects_without_notice_review_or_recombination(self):
        self.args.require_ready = True
        with self.assertRaises(ValueError):
            bundle.make_bundle(self.args)
        self.assertFalse(Path(self.args.output).exists())

    def test_native_payload_mismatch_and_missing_native_rejected(self):
        for value in (b'changed', None):
            files = dict(self.apk_files)
            if value is None:
                del files['lib/arm64-v8a/libspake2.so']
            else:
                files['lib/arm64-v8a/libspake2.so'] = value
            self.write('app.apk', zip_bytes(files))
            with self.assertRaises(ValueError):
                bundle.make_bundle(self.args)

    def test_bundle_optional_strip_tool_retains_pinned_artifact_and_native_provenance(self):
        stripped = b'exact stripped conscrypt'
        self.apk_files['lib/arm64-v8a/libconscrypt_jni.so'] = stripped
        self.apk = zip_bytes(self.apk_files)
        self.write('app.apk', self.apk)
        self.inventory['apkSha256'] = bundle.digest(self.apk)
        self.save_inventory()
        self.write('llvm-strip.exe', b'synthetic tool')
        self.args.strip_tool = str(self.root / 'llvm-strip.exe')
        def strip(command, **options):
            self.assertEqual(b'conscrypt', Path(command[4]).read_bytes())
            Path(command[3]).write_bytes(stripped)
            return subprocess.CompletedProcess(command, 0)
        with patch.object(bundle.subprocess, 'run', side_effect=strip):
            bundle.make_bundle(self.args)
        archive = bundle.checked_zip(Path(self.args.output).read_bytes())
        manifest = json.loads(archive.read('RELEASE_BUNDLE_MANIFEST.json'))
        rows = {row['path']: row for row in manifest['packagedLibraries']}
        conscrypt = rows['lib/arm64-v8a/libconscrypt_jni.so']
        self.assertEqual(bundle.digest(b'conscrypt'), conscrypt['originalSha256'])
        self.assertEqual(bundle.digest(stripped), conscrypt['sha256'])
        self.assertEqual(bundle.digest(b'synthetic tool'), conscrypt['transformation']['toolSha256'])
        self.assertEqual({'kind': 'identity'}, rows['lib/arm64-v8a/libspake2.so']['transformation'])
        inventory = json.loads(archive.read('RESOLVED_COMPONENTS.json'))
        self.assertEqual(bundle.CONSCRYPT_AAR_HASH, inventory['components'][1]['sha256'])
        bundle.check_bundle(self.args.output)

    def test_unattributed_font_rejected(self):
        files = dict(self.apk_files)
        files['assets/fonts/MaterialIcons.ttf'] = b'other font'
        self.write('app.apk', zip_bytes(files))
        with self.assertRaises(ValueError):
            bundle.make_bundle(self.args)

    def test_inventory_cannot_export_private_paths_or_unknown_fields(self):
        self.inventory['inputPath'] = 'private/cache'
        self.save_inventory()
        with self.assertRaises(ValueError):
            bundle.make_bundle(self.args)

    def test_required_component_hash_and_missing_component_rejected(self):
        self.inventory['components'][0]['sha256'] = '0' * 64
        self.save_inventory()
        with self.assertRaises(ValueError):
            bundle.make_bundle(self.args)
        self.inventory['components'].pop(0)
        self.save_inventory()
        with self.assertRaises(ValueError):
            bundle.make_bundle(self.args)

    def test_inventory_bound_to_exact_apk_and_commit(self):
        for key in ('apkSha256', 'appSourceCommit'):
            old = self.inventory[key]
            self.inventory[key] = '0' * len(old)
            self.save_inventory()
            with self.assertRaises(ValueError):
                bundle.make_bundle(self.args)
            self.inventory[key] = old

    def test_notice_traversal_and_missing_notice_rejected(self):
        for name in ('../outside.txt', 'missing.txt'):
            self.inventory['components'][0]['noticeFiles'] = [name]
            self.save_inventory()
            with self.assertRaises(ValueError):
                bundle.make_bundle(self.args)

    def test_stale_recombination_receipt_rejected(self):
        self.write('receipt.json', bundle.json_bytes({'schema': 1, 'releaseApkSha256': '0' * 64}))
        self.args.recombination = str(self.root / 'receipt.json')
        with self.assertRaises(ValueError):
            bundle.make_bundle(self.args)

    def test_bundle_changed_after_generation_fails_hash_check(self):
        bundle.make_bundle(self.args)
        archive = bundle.checked_zip(Path(self.args.output).read_bytes())
        files = {n: archive.read(n) for n in archive.namelist()}
        files['application.apk'] += b'change'
        self.write('tampered.zip', zip_bytes(files))
        with self.assertRaises(ValueError):
            bundle.check_bundle(self.root / 'tampered.zip')


class NativeStripTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tool = self.root / 'llvm-strip.exe'
        self.tool.write_bytes(b'synthetic executable fixture')
        self.source = b'original ELF with debug symbols'
        self.stripped = b'original ELF without debug symbols'
        self.aar = bundle.checked_zip(zip_bytes({'jni/arm64-v8a/libconscrypt_jni.so': self.source}))

    def apk(self, data):
        return bundle.checked_zip(zip_bytes({'lib/arm64-v8a/libconscrypt_jni.so': data}))

    def strip(self, command, **options):
        self.assertEqual([str(self.tool.resolve()), '--strip-unneeded', '-o'], command[:3])
        output, source = map(Path, command[3:])
        self.assertEqual(output.parent, source.parent)
        self.assertEqual(self.source, source.read_bytes())
        self.assertIs(options['stdin'], subprocess.DEVNULL)
        self.assertIs(options['stdout'], subprocess.DEVNULL)
        self.assertIs(options['stderr'], subprocess.DEVNULL)
        self.assertFalse(options['shell'])
        self.assertGreater(options['timeout'], 0)
        self.assertLessEqual(options['timeout'], 30)
        output.write_bytes(self.stripped)
        return subprocess.CompletedProcess(command, 0)

    def test_direct_identity_records_original_and_never_invokes_tool(self):
        with patch.object(bundle.subprocess, 'run') as run:
            rows = bundle.native_matches(self.apk(self.source), self.aar, 'libconscrypt_jni.so', self.tool)
        run.assert_not_called()
        self.assertEqual(bundle.digest(self.source), rows[0]['sha256'])
        self.assertEqual(rows[0]['sha256'], rows[0]['originalSha256'])
        self.assertEqual({'kind': 'identity'}, rows[0]['transformation'])

    def test_exact_actual_strip_output_and_hash_provenance(self):
        with patch.object(bundle.subprocess, 'run', side_effect=self.strip) as run:
            rows = bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', self.tool)
        self.assertEqual(2, run.call_count)
        self.assertEqual(bundle.digest(self.stripped), rows[0]['sha256'])
        self.assertEqual(bundle.digest(self.source), rows[0]['originalSha256'])
        self.assertEqual({'kind': 'llvm-strip', 'toolSha256': bundle.digest(self.tool.read_bytes()),
                          'arguments': ['--strip-unneeded']}, rows[0]['transformation'])
        self.assertNotIn(str(self.root), json.dumps(rows))

    def test_unexpected_binary_change_rejected_even_with_strip_tool(self):
        with patch.object(bundle.subprocess, 'run', side_effect=self.strip), self.assertRaises(ValueError):
            bundle.native_matches(self.apk(self.stripped + b'changed code'), self.aar, 'libconscrypt_jni.so', self.tool)

    def test_mismatch_without_explicit_strip_tool_rejected(self):
        with self.assertRaises(ValueError):
            bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so')

    def test_missing_wrong_named_empty_or_oversized_tool_rejected(self):
        for name, data in [('missing/llvm-strip.exe', None), ('strip.exe', b'fixture'),
                           ('empty/llvm-strip.exe', b''), ('large/llvm-strip.exe', b'12345')]:
            path = self.root / name
            if data is not None:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            with self.subTest(name=name), patch.object(bundle, 'MAX_STRIP_TOOL', 4), self.assertRaises(ValueError):
                bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', path)

    def test_nonzero_missing_output_timeout_and_malformed_executable_rejected(self):
        effects = [subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0),
                   subprocess.TimeoutExpired([], 30), OSError('private local failure')]
        for effect in effects:
            with self.subTest(effect=type(effect).__name__), patch.object(bundle.subprocess, 'run') as run:
                if isinstance(effect, Exception):
                    run.side_effect = effect
                else:
                    run.return_value = effect
                with self.assertRaises(ValueError) as error:
                    bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', self.tool)
                self.assertNotIn('private local failure', str(error.exception))

    def test_real_malformed_executable_does_not_allow_native_match(self):
        # A present file with the required name is still not evidence that it ran.
        with self.assertRaises(ValueError):
            bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', self.tool)

    def test_output_bounds_and_changed_tool_rejected(self):
        def oversize(command, **options):
            Path(command[3]).write_bytes(b'x' * (len(self.stripped) + 1))
            return subprocess.CompletedProcess(command, 0)
        with patch.object(bundle, 'MAX_ENTRY', len(self.stripped)), patch.object(bundle.subprocess, 'run', side_effect=oversize) as run, self.assertRaises(ValueError):
            bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', self.tool)
        self.assertEqual(1, run.call_count)
        def swapped_tool(command, **options):
            result = self.strip(command, **options)
            self.tool.write_bytes(b'different executable')
            return result
        with patch.object(bundle.subprocess, 'run', side_effect=swapped_tool), self.assertRaises(ValueError):
            bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', self.tool)

    def test_nondeterministic_output_rejected(self):
        count = 0
        def inconsistent(command, **options):
            nonlocal count
            result = self.strip(command, **options)
            count += 1
            if count == 2:
                Path(command[3]).write_bytes(self.stripped + b'changed')
            return result
        with patch.object(bundle.subprocess, 'run', side_effect=inconsistent), self.assertRaises(ValueError):
            bundle.native_matches(self.apk(self.stripped), self.aar, 'libconscrypt_jni.so', self.tool)


if __name__ == '__main__':
    unittest.main()
