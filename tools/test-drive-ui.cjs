'use strict';
// Host tests for the bridge/status boundary, with no Google account or Android device.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.join(__dirname, '../src/services/NativeDriveBackup.ts'), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const reactNative = { NativeModules: {}, Platform: { OS: 'android' } };
const exported = {};
vm.runInNewContext(js, { exports: exported, module: { exports: exported }, require: name => {
  assert.equal(name, 'react-native'); return reactNative;
} }, { filename: 'NativeDriveBackup.js' });
const { NativeDriveBackup, normalizeDriveBackupStatus, DriveBackupError, driveErrorMessage } = exported;
const status = {
  enabled: false, connected: false, accountEmail: null, folderId: null, folderName: null,
  phase: 'disconnected', queuedCount: 0, uploadedCount: 0, failedCount: 0, uploadingId: null,
  uploadedBytes: 0, totalBytes: 0, errorCode: null, errorMessage: null,
};
let passed = 0;
function check(name, fn) { fn(); passed += 1; }
async function checkAsync(name, fn) { await fn(); passed += 1; }
function rejectsStatus(override) { assert.throws(() => normalizeDriveBackupStatus({ ...status, ...override }), error => error instanceof DriveBackupError && error.code === 'DRIVE_STATUS_INVALID'); }

async function main() {
  check('all native phases are preserved', () => { for (const phase of ['disconnected', 'ready', 'uploading', 'paused', 'needsConsent', 'error']) assert.equal(normalizeDriveBackupStatus({ ...status, phase }).phase, phase); });
  check('status cannot carry extra credential fields into UI', () => { const normalized = normalizeDriveBackupStatus({ ...status, accessToken: 'synthetic-private', refreshToken: 'synthetic-private' }); assert.equal('accessToken' in normalized, false); assert.equal('refreshToken' in normalized, false); });
  check('unknown phase rejected', () => rejectsStatus({ phase: 'success' }));
  check('missing field rejected', () => rejectsStatus({ folderId: undefined }));
  check('nonboolean enable rejected', () => rejectsStatus({ enabled: 'true' }));
  check('negative count rejected', () => rejectsStatus({ queuedCount: -1 }));
  check('nonfinite progress rejected', () => rejectsStatus({ uploadedBytes: NaN }));
  check('fractional count rejected', () => rejectsStatus({ failedCount: 0.5 }));
  check('invalid item state rejected', () => rejectsStatus({ items: [{ id: 'synthetic-id', state: 'complete' }] }));
  check('oversized item list rejected', () => rejectsStatus({ items: Array(1001).fill({ id: 'synthetic-id', state: 'pending' }) }));
  check('public item status copied without extras', () => { const item = normalizeDriveBackupStatus({ ...status, items: [{ id: 'synthetic-id', state: 'uploaded', errorCode: null, token: 'synthetic-private' }] }).items[0]; assert.equal(item.state, 'uploaded'); assert.equal('token' in item, false); });
  check('configuration errors have actionable setup copy', () => assert.match(driveErrorMessage('CONFIGURATION_REQUIRED'), /Google Cloud/));
  check('native sanitized status copy preserved for unknown codes', () => assert.equal(driveErrorMessage('NEW_SAFE_CODE', 'הפעולה דורשת אישור'), 'הפעולה דורשת אישור'));
  check('all exact native mutation/transport codes mapped without raw bodies', () => { for (const code of ['FOREGROUND_REQUIRED', 'CONNECTION_BUSY', 'RECORDING_BUSY', 'NOT_CONNECTED', 'UPLOAD_FAILED', 'HTTP_ERROR', 'CREATE_CONFLICT']) assert.equal(driveErrorMessage(code, 'synthetic private provider body').includes('synthetic private'), false); });
  await checkAsync('missing module fails truthfully', () => assert.rejects(NativeDriveBackup.getStatus(), error => error.code === 'DRIVE_MODULE_UNAVAILABLE'));
  await checkAsync('nonandroid fails truthfully', async () => { reactNative.Platform.OS = 'ios'; await assert.rejects(NativeDriveBackup.getStatus(), error => error.code === 'DRIVE_ANDROID_ONLY'); reactNative.Platform.OS = 'android'; });
  await checkAsync('all six methods return validated actual native status', async () => {
    const calls = [];
    reactNative.NativeModules.DriveBackup = Object.fromEntries(['getStatus', 'connect', 'chooseFolder', 'setEnabled', 'retryPending', 'disconnect'].map(name => [name, async (...args) => { calls.push([name, ...args]); return { ...status, connected: true, accountEmail: 'preview@example.test' }; }]));
    for (const name of ['getStatus', 'connect', 'chooseFolder', 'setEnabled', 'retryPending', 'disconnect']) assert.equal((await NativeDriveBackup[name](true)).accountEmail, 'preview@example.test');
    assert.equal(calls.length, 6); assert.equal(calls[3][1], true);
  });
  await checkAsync('known rejection code mapped without raw provider body', async () => { reactNative.NativeModules.DriveBackup.connect = async () => { throw { code: 'CONFIGURATION_REQUIRED', message: 'synthetic token must not be shown' }; }; await assert.rejects(NativeDriveBackup.connect(), error => error.code === 'CONFIGURATION_REQUIRED' && error.message.includes('Google Cloud') && !error.message.includes('synthetic token')); });
  await checkAsync('unknown rejection never surfaces raw provider response', async () => { reactNative.NativeModules.DriveBackup.connect = async () => { throw { code: 'UNEXPECTED', message: 'synthetic private provider body' }; }; await assert.rejects(NativeDriveBackup.connect(), error => !error.message.includes('synthetic private')); });
  process.stdout.write(`Drive UI bridge checks: ${passed} passed\n`);
}
main().catch(error => { process.stderr.write(String(error) + '\n'); process.exitCode = 1; });
