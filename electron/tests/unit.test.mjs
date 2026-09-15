import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const shell = require('../main.js');

test('session token is 32-byte base64url without padding', () => {
  const token = shell.createSessionToken(Buffer.alloc(32, 0xff));
  assert.equal(token.length, 43);
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  assert.throws(() => shell.createSessionToken(Buffer.alloc(31)), /32 random bytes/);
});

test('uuid helper creates RFC 4122 v4 identifiers', () => {
  const id = shell.uuidV4();
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

test('readiness parser accepts only the constrained protocol line', () => {
  assert.deepEqual(shell.parseReadinessLine('LEDGERX_READY {"port":49152,"protocol":"1"}'), {
    port: 49152,
    protocol: '1'
  });
  assert.equal(shell.parseReadinessLine('ordinary log line'), null);
  assert.throws(() => shell.parseReadinessLine('LEDGERX_READY {"port":0,"protocol":"1"}'));
  assert.throws(() => shell.parseReadinessLine('LEDGERX_READY {"port":49152,"protocol":"2"}'));
  assert.throws(() => shell.parseReadinessLine('LEDGERX_READY []'));
});

test('authorization injection matcher is origin, path and resource-type scoped', () => {
  const origin = 'http://127.0.0.1:49152';
  assert.equal(shell.isTargetApiRequest({
    url: `${origin}/api/v1/system/status`,
    resourceType: 'fetch'
  }, origin), true);
  assert.equal(shell.isTargetApiRequest({
    url: `${origin}/health/live`,
    resourceType: 'fetch'
  }, origin), false);
  assert.equal(shell.isTargetApiRequest({
    url: 'http://127.0.0.1:49153/api/v1/system/status',
    resourceType: 'fetch'
  }, origin), false);
  assert.equal(shell.isTargetApiRequest({
    url: `${origin}/api/v1/system/status`,
    resourceType: 'mainFrame'
  }, origin), false);
});

test('status payload validation follows the shared contract', async () => {
  const fixture = JSON.parse(await readFile(new URL('../../docs/contracts/api-v1/system/status-starting.json', import.meta.url)));
  assert.equal(shell.validateStatusPayload(fixture), true);
  assert.equal(shell.validateStatusPayload({ ...fixture, data: { ...fixture.data, apiVersion: '2.0' } }), false);
  assert.equal(shell.validateStatusPayload({ ...fixture, data: { ...fixture.data, state: 'UNKNOWN' } }), false);
  assert.equal(shell.validateStatusPayload({ ...fixture, data: { ...fixture.data, capabilities: [] } }), false);
});

test('logs redact session tokens, bearer values and paths', () => {
  const token = 'A'.repeat(43);
  const result = shell.sanitizeLog(`token=${token} Authorization: Bearer ${token} path=C:\\secret`, [token, 'C:\\secret']);
  assert.equal(result.includes(token), false);
  assert.equal(result.includes('C:\\secret'), false);
  assert.match(result, /Bearer \[redacted\]/);
});

test('test Java args must be a JSON string array', () => {
  assert.deepEqual(shell.parseJavaArgs('["-cp","classes","com.ledgerx.http.HttpServerMain"]'), [
    '-cp', 'classes', 'com.ledgerx.http.HttpServerMain'
  ]);
  assert.equal(shell.parseJavaArgs(''), null);
  assert.throws(() => shell.parseJavaArgs('{"bad":true}'));
  assert.throws(() => shell.parseJavaArgs('[1]'));
});

test('desktop data root prefers the test override or LOCALAPPDATA and rejects unsafe values', () => {
  const localAppData = process.platform === 'win32'
    ? 'C:\\Users\\ledgerx-test\\AppData\\Local'
    : '/tmp/ledgerx-test-local';
  assert.equal(
    shell.resolveDataDir({}, { LOCALAPPDATA: localAppData }),
    path.join(localAppData, 'LedgerX')
  );
  assert.equal(
    shell.resolveDataDir({ dataDir: path.join(localAppData, 'isolated') }, { LOCALAPPDATA: '' }),
    path.join(localAppData, 'isolated')
  );
  assert.throws(() => shell.resolveDataDir({ dataDir: 'relative-data' }, { LOCALAPPDATA: localAppData }), /absolute/);
  assert.throws(() => shell.resolveDataDir({}, { LOCALAPPDATA: '' }), /LOCALAPPDATA/);
});

test('desktop config ignores an inherited generic data directory', () => {
  const config = shell.resolveBackendConfig({
    LEDGERX_DATA_DIR: 'C:\\inherited\\must-not-win',
    LEDGERX_TEST_JAVA_ARGS_JSON: '["-version"]'
  });
  assert.equal(config.dataDir, null);

  const testConfig = shell.resolveBackendConfig({
    LEDGERX_DATA_DIR: 'C:\\inherited\\must-not-win',
    LEDGERX_TEST_DATA_DIR: 'C:\\isolated\\test-data',
    LEDGERX_TEST_JAVA_ARGS_JSON: '["-version"]'
  });
  assert.equal(testConfig.dataDir, 'C:\\isolated\\test-data');
});

test('BrowserWindow security baseline is present in the main process', async () => {
  const source = await readFile(new URL('../main.js', import.meta.url), 'utf8');
  assert.match(source, /nodeIntegration:\s*false/);
  assert.match(source, /contextIsolation:\s*true/);
  assert.match(source, /sandbox:\s*true/);
  assert.match(source, /devTools:\s*false/);
  assert.match(source, /setWindowOpenHandler\(\(\) => \(\{ action: 'deny' \}\)\)/);
  assert.match(source, /setPermissionRequestHandler/);
  assert.match(source, /setPermissionCheckHandler/);
  assert.doesNotMatch(source, /(?:--no-sandbox|sandbox:\s*false)/);
  assert.match(source, /appendSwitch\('in-process-gpu'\)/);
  assert.match(source, /retrying:\s*false/);
  assert.match(source, /!runtime\.retrying/);
  const errorPage = await readFile(new URL('../error.html', import.meta.url), 'utf8');
  assert.match(errorPage, /window\.desktop \? await window\.desktop\.retryBackend\(\) : null/);
  assert.doesNotMatch(errorPage, /const desktop = window\.desktop/);
});
