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
const { NativeDriveBackup, normalizeDriveBackupStatus, DriveBackupError, driveErrorMessage, driveActionErrorMessage } = exported;
const status = {
  enabled: false, connected: false, accountEmail: null, folderId: null, folderName: null,
  phase: 'disconnected', queuedCount: 0, uploadedCount: 0, failedCount: 0, uploadingId: null,
  uploadedBytes: 0, totalBytes: 0, errorCode: null, errorMessage: null,
};
let passed = 0;
function check(name, fn) { fn(); passed += 1; }
async function checkAsync(name, fn) { await fn(); passed += 1; }
function rejectsStatus(override) { assert.throws(() => normalizeDriveBackupStatus({ ...status, ...override }), error => error instanceof DriveBackupError && error.code === 'DRIVE_STATUS_INVALID'); }
const diagnostic = { action: 'connect', stage: 'authorize', code: 'CONFIGURATION_REQUIRED', authStatusCode: 10, activityResultCode: 0 };
const diagnosticCodes = ['CONFIGURATION_REQUIRED', 'AUTH_REQUIRED', 'ACCOUNT_CHANGED', 'FOLDER_UNAVAILABLE', 'NETWORK', 'RATE_LIMIT', 'STORAGE_FULL', 'LOCAL_QUEUE_UNAVAILABLE', 'FOREGROUND_REQUIRED', 'RECORDING_BUSY', 'CONNECTION_BUSY', 'NOT_CONNECTED', 'UPLOAD_FAILED', 'HTTP_ERROR', 'REMOTE_MISMATCH'];
const configuredStatus = { ...status, enabled: true, connected: true, accountEmail: 'preview@example.test', folderId: 'synthetic-folder', folderName: 'תיקיית בדיקה', phase: 'ready', queuedCount: 3, uploadedCount: 2 };

// Render the card as a host tree to check real conditional UI without a device or account.
let cardState;
const cardSource = fs.readFileSync(path.join(__dirname, '../src/components/DriveBackupCard.tsx'), 'utf8');
const cardJs = ts.transpileModule(cardSource, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, jsx: ts.JsxEmit.React, esModuleInterop: true } }).outputText;
const cardExported = {};
vm.runInNewContext(cardJs, { exports: cardExported, module: { exports: cardExported }, require: name => {
  if (name === 'react') return { createElement: (type, props, ...children) => ({ type, props, children }) };
  if (name === 'react-native') return { ...Object.fromEntries(['View', 'Text', 'Switch', 'TouchableOpacity', 'ActivityIndicator'].map(name => [name, name])), StyleSheet: { create: value => value }, Alert: { alert: () => {} } };
  if (name === '../hooks/useDriveBackup') return { useDriveBackup: () => cardState };
  if (name === '../services/NativeDriveBackup') return exported;
  if (name === '../theme') return { colors: {}, ui: {} };
  throw new Error(`Unexpected card dependency ${name}`);
} }, { filename: 'DriveBackupCard.js' });
function textFrom(node) {
  if (node === null || node === undefined || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textFrom).join(' ');
  if (node.type === 'Text') return node.children.map(textFrom).join('');
  return textFrom(node.children);
}
function renderCard(nextStatus, overrides = {}) {
  cardState = { status: nextStatus, checking: false, action: null, error: null, errorAction: null, refresh: () => {}, run: () => {}, ...overrides };
  return textFrom(cardExported.default({ isRecording: false, recorderBusy: false }));
}

// Exercise hook state transitions with controlled focus/AppState and no real polling timer.
function hookHarness() {
  const slots = []; let cursor = 0; const effects = [];
  const sameDeps = (a, b) => a && b && a.length === b.length && a.every((value, index) => value === b[index]);
  const react = {
    useState(initial) { const index = cursor++; if (!slots[index]) slots[index] = { value: initial }; return [slots[index].value, value => { slots[index].value = value; }]; },
    useRef(initial) { const index = cursor++; if (!slots[index]) slots[index] = { current: initial }; return slots[index]; },
    useCallback(callback, deps) { const index = cursor++; if (!slots[index] || !sameDeps(slots[index].deps, deps)) slots[index] = { callback, deps }; return slots[index].callback; },
    useEffect(callback, deps) { const index = cursor++; if (!slots[index] || !sameDeps(slots[index].deps, deps)) { slots[index]?.cleanup?.(); slots[index] = { deps }; effects.push(() => { slots[index].cleanup = callback(); }); } },
  };
  const hookJs = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/hooks/useDriveBackup.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  const hookExported = {};
  vm.runInNewContext(hookJs, { exports: hookExported, module: { exports: hookExported }, setInterval: () => 1, clearInterval: () => {}, require: name => {
    if (name === 'react') return react;
    if (name === 'react-native') return { AppState: { currentState: 'active', addEventListener: () => ({ remove() {} }) } };
    if (name === '@react-navigation/native') return { useFocusEffect: callback => react.useEffect(callback, [callback]) };
    if (name === '../services/NativeDriveBackup') return exported;
    throw new Error(`Unexpected hook dependency ${name}`);
  } }, { filename: 'useDriveBackup.js' });
  return {
    render() { cursor = 0; const result = hookExported.useDriveBackup(); while (effects.length) effects.shift()(); return result; },
    dispose() { for (const slot of slots) slot?.cleanup?.(); },
  };
}
const settle = () => new Promise(resolve => setImmediate(resolve));

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
  check('unknown native messages never become UI copy', () => { for (const code of [null, 'NEW_SAFE_CODE', 'toString', '__proto__']) assert.equal(driveErrorMessage(code, 'synthetic private provider body').includes('synthetic private'), false); });
  check('all exact native mutation/transport codes mapped without raw bodies', () => { for (const code of ['FOREGROUND_REQUIRED', 'CONNECTION_BUSY', 'RECORDING_BUSY', 'NOT_CONNECTED', 'UPLOAD_FAILED', 'HTTP_ERROR', 'CREATE_CONFLICT']) assert.equal(driveErrorMessage(code, 'synthetic private provider body').includes('synthetic private'), false); });
  check('legacy status without diagnostics remains compatible', () => assert.equal('lastActionError' in normalizeDriveBackupStatus(status), false));
  check('explicit cleared diagnostic preserved', () => assert.equal(normalizeDriveBackupStatus({ ...status, lastActionError: null }).lastActionError, null));
  check('diagnostic actions stages and native code whitelist supported', () => {
    for (const action of ['connect', 'folder']) for (const stage of ['authorize', 'pickerResult', 'selection']) for (const code of diagnosticCodes) {
      const normalized = normalizeDriveBackupStatus({ ...status, lastActionError: { action, stage, code } }).lastActionError;
      assert.equal(normalized.action, action); assert.equal(normalized.stage, stage); assert.equal(normalized.code, code);
    }
  });
  check('diagnostics strip credentials and provider messages', () => {
    const normalized = normalizeDriveBackupStatus({ ...status, lastActionError: { ...diagnostic, message: 'synthetic private provider body', accessToken: 'synthetic-private', url: 'https://synthetic.invalid/?token=private' } }).lastActionError;
    assert.deepEqual(Object.keys(normalized).sort(), ['action', 'activityResultCode', 'authStatusCode', 'code', 'stage']);
    assert.equal(normalized.authStatusCode, 10); assert.equal(normalized.activityResultCode, 0);
  });
  check('bounded diagnostic integer endpoints preserved', () => {
    for (const authStatusCode of [0, 65535]) for (const activityResultCode of [-65535, -1, 0, 65535]) {
      const normalized = normalizeDriveBackupStatus({ ...status, lastActionError: { ...diagnostic, authStatusCode, activityResultCode } }).lastActionError;
      assert.equal(normalized.authStatusCode, authStatusCode); assert.equal(normalized.activityResultCode, activityResultCode);
    }
  });
  check('malformed optional diagnostics cannot break a valid current queue', () => {
    const malformed = [false, [], 'synthetic private', 42,
      { ...diagnostic, action: 'upload' }, { ...diagnostic, stage: 'response' }, { ...diagnostic, code: 'NEW_UNSAFE_CODE' },
      { ...diagnostic, code: 'toString' }, { ...diagnostic, code: '__proto__' }, { ...diagnostic, code: 'x'.repeat(100000) },
      ...[NaN, Infinity, -1, 65536, 1.5, '10', null].map(authStatusCode => ({ ...diagnostic, authStatusCode })),
      ...[NaN, Infinity, -65536, 65536, 1.5, '0', null].map(activityResultCode => ({ ...diagnostic, activityResultCode }))];
    for (const lastActionError of malformed) {
      const normalized = normalizeDriveBackupStatus({ ...configuredStatus, lastActionError });
      assert.equal(normalized.lastActionError, null); assert.equal(normalized.phase, 'ready'); assert.equal(normalized.enabled, true); assert.equal(normalized.queuedCount, 3);
    }
  });
  check('failed configuration action preserves ready and uploading queue status', () => {
    for (const phase of ['ready', 'uploading']) {
      const normalized = normalizeDriveBackupStatus({ ...configuredStatus, phase, lastActionError: diagnostic });
      assert.equal(normalized.phase, phase); assert.equal(normalized.errorCode, null); assert.equal(normalized.errorMessage, null); assert.equal(normalized.enabled, true); assert.equal(normalized.queuedCount, 3); assert.equal(normalized.uploadedCount, 2);
    }
  });
  check('status error fields also contain only app-owned copy', () => {
    const normalized = normalizeDriveBackupStatus({ ...status, errorCode: 'synthetic-private-code', errorMessage: 'synthetic private provider body' });
    assert.equal(normalized.errorCode, 'DRIVE_ACTION_FAILED'); assert.equal(normalized.errorMessage.includes('synthetic private'), false);
    assert.equal(normalizeDriveBackupStatus({ ...status, errorCode: 'CONFIGURATION_REQUIRED', errorMessage: 'synthetic private' }).errorMessage, driveErrorMessage('CONFIGURATION_REQUIRED'));
  });
  check('action errors describe setup attempts without claiming upload failure', () => { assert.match(driveActionErrorMessage('CONFIGURATION_REQUIRED'), /Google Cloud/); assert.equal(driveActionErrorMessage('UPLOAD_FAILED').includes('העלאת אחת'), false); assert.equal(driveActionErrorMessage('toString').includes('function'), false); });
  check('ready card displays setup failure separately from active backup', () => {
    const text = renderCard(normalizeDriveBackupStatus({ ...configuredStatus, lastActionError: diagnostic }));
    assert.match(text, /גיבוי ההקלטות פעיל/); assert.match(text, /ניסיון החיבור האחרון לא הושלם/); assert.match(text, /CONFIGURATION_REQUIRED/); assert.match(text, /קוד אישור Google: 10/); assert.match(text, /קוד תוצאת הבחירה: 0/);
    assert.equal(text.includes('הגיבוי דורש בדיקה'), false); assert.equal(text.includes('לא ניתן לגבות עד'), false);
  });
  check('uploading card retains progress after folder attempt failed', () => {
    const text = renderCard(normalizeDriveBackupStatus({ ...configuredStatus, phase: 'uploading', uploadedBytes: 25, totalBytes: 100, lastActionError: { action: 'folder', stage: 'pickerResult', code: 'FOLDER_UNAVAILABLE', activityResultCode: -1 } }));
    assert.match(text, /מעלה הקלטה ל־Google Drive/); assert.match(text, /25%/); assert.match(text, /ניסיון בחירת התיקייה האחרון לא הושלם/); assert.match(text, /קוד תוצאת הבחירה: -1/); assert.equal(text.includes('הגיבוי דורש בדיקה'), false);
  });
  check('legacy immediate setup rejection has its own attempt label', () => {
    const text = renderCard(configuredStatus, { error: new DriveBackupError('UPLOAD_FAILED', 'synthetic private provider body'), errorAction: 'connect' });
    assert.match(text, /גיבוי ההקלטות פעיל/); assert.match(text, /ניסיון החיבור האחרון לא הושלם/); assert.equal(text.includes('synthetic private'), false); assert.equal(text.includes('העלאת אחת'), false);
  });
  check('current queue failure still has separate upload copy', () => {
    const text = renderCard(normalizeDriveBackupStatus({ ...configuredStatus, phase: 'error', errorCode: 'UPLOAD_FAILED', errorMessage: 'synthetic private provider body', lastActionError: diagnostic }));
    assert.match(text, /הגיבוי דורש בדיקה/); assert.match(text, /העלאת אחת ההקלטות לא הושלמה/); assert.match(text, /ניסיון החיבור האחרון לא הושלם/); assert.equal(text.includes('synthetic private'), false);
  });
  await checkAsync('missing module fails truthfully', () => assert.rejects(NativeDriveBackup.getStatus(), error => error.code === 'DRIVE_MODULE_UNAVAILABLE'));
  await checkAsync('nonandroid fails truthfully', async () => { reactNative.Platform.OS = 'ios'; await assert.rejects(NativeDriveBackup.getStatus(), error => error.code === 'DRIVE_ANDROID_ONLY'); reactNative.Platform.OS = 'android'; });
  await checkAsync('all six methods return validated actual native status', async () => {
    const calls = [];
    reactNative.NativeModules.DriveBackup = Object.fromEntries(['getStatus', 'connect', 'chooseFolder', 'setEnabled', 'retryPending', 'disconnect'].map(name => [name, async (...args) => { calls.push([name, ...args]); return { ...status, connected: true, accountEmail: 'preview@example.test' }; }]));
    for (const name of ['getStatus', 'connect', 'chooseFolder', 'setEnabled', 'retryPending', 'disconnect']) assert.equal((await NativeDriveBackup[name](true)).accountEmail, 'preview@example.test');
    assert.equal(calls.length, 6); assert.equal(calls[3][1], true);
  });
  await checkAsync('known rejection code mapped without raw provider body', async () => { reactNative.NativeModules.DriveBackup.connect = async () => { throw { code: 'CONFIGURATION_REQUIRED', message: 'synthetic token must not be shown' }; }; await assert.rejects(NativeDriveBackup.connect(), error => error.code === 'CONFIGURATION_REQUIRED' && error.message.includes('Google Cloud') && !error.message.includes('synthetic token')); });
  await checkAsync('unknown rejection never surfaces raw code or provider response', async () => { reactNative.NativeModules.DriveBackup.connect = async () => { throw { code: 'synthetic-private-code', message: 'synthetic private provider body' }; }; await assert.rejects(NativeDriveBackup.connect(), error => error.code === 'DRIVE_ACTION_FAILED' && !error.message.includes('synthetic private')); });
  await checkAsync('native getStatus restores bounded diagnostics after screen remount', async () => {
    reactNative.NativeModules.DriveBackup.getStatus = async () => ({ ...configuredStatus, lastActionError: { ...diagnostic, message: 'synthetic private body' } });
    for (let screenMount = 0; screenMount < 2; screenMount += 1) {
      const next = await NativeDriveBackup.getStatus(); assert.equal(next.lastActionError.authStatusCode, 10); assert.equal(next.phase, 'ready'); assert.equal('message' in next.lastActionError, false);
    }
  });
  await checkAsync('native diagnostic replaces temporary setup rejection without changing current queue', async () => {
    reactNative.NativeModules.DriveBackup.getStatus = async () => ({ ...configuredStatus, lastActionError: null });
    const hook = hookHarness(); hook.render(); await settle(); let state = hook.render();
    await state.run('connect', async () => { throw new DriveBackupError('CONFIGURATION_REQUIRED', driveErrorMessage('CONFIGURATION_REQUIRED')); });
    state = hook.render(); assert.equal(state.errorAction, 'connect'); assert.equal(state.status.phase, 'ready');
    reactNative.NativeModules.DriveBackup.getStatus = async () => ({ ...configuredStatus, lastActionError: diagnostic });
    await state.refresh(); state = hook.render(); assert.equal(state.error, null); assert.equal(state.errorAction, null); assert.equal(state.status.lastActionError.authStatusCode, 10); assert.equal(state.status.phase, 'ready');
    reactNative.NativeModules.DriveBackup.getStatus = async () => ({ ...configuredStatus, lastActionError: null });
    await state.refresh(); state = hook.render(); assert.equal(state.status.lastActionError, null); assert.equal(state.error, null); hook.dispose();
  });
  await checkAsync('legacy build retains temporary setup diagnosis until another action', async () => {
    reactNative.NativeModules.DriveBackup.getStatus = async () => configuredStatus;
    const hook = hookHarness(); hook.render(); await settle(); let state = hook.render();
    await state.run('folder', async () => { throw new DriveBackupError('FOLDER_UNAVAILABLE', driveErrorMessage('FOLDER_UNAVAILABLE')); });
    state = hook.render(); await state.refresh(); state = hook.render(); assert.equal(state.errorAction, 'folder'); assert.equal(state.error.code, 'FOLDER_UNAVAILABLE'); assert.equal(state.status.phase, 'ready');
    await state.run('folder', async () => configuredStatus); state = hook.render(); assert.equal(state.error, null); assert.equal(state.errorAction, null); hook.dispose();
  });
  await checkAsync('setup diagnostic polling does not dismiss an unrelated failed queue action', async () => {
    reactNative.NativeModules.DriveBackup.getStatus = async () => ({ ...configuredStatus, lastActionError: null });
    const hook = hookHarness(); hook.render(); await settle(); let state = hook.render();
    await state.run('retry', async () => { throw new DriveBackupError('NETWORK', driveErrorMessage('NETWORK')); });
    state = hook.render(); await state.refresh(); state = hook.render(); assert.equal(state.errorAction, 'retry'); assert.equal(state.error.code, 'NETWORK'); hook.dispose();
  });
  process.stdout.write(`Drive UI bridge checks: ${passed} passed\n`);
}
main().catch(error => { process.stderr.write(String(error) + '\n'); process.exitCode = 1; });
