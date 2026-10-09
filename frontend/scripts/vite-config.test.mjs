import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';
import { createDevProxy } from '../vite.config.js';

class FakeProxyServer extends EventEmitter {}

class FakeProxyRequest {
  constructor(initialHeaders = {}) {
    this.headers = new Map(
      Object.entries(initialHeaders).map(([name, value]) => [name.toLowerCase(), value]),
    );
  }

  setHeader(name, value) {
    this.headers.set(name.toLowerCase(), value);
  }

  removeHeader(name) {
    this.headers.delete(name.toLowerCase());
  }

  getHeader(name) {
    return this.headers.get(name.toLowerCase());
  }
}

function assertConfigurationError(action, forbiddenText = '') {
  assert.throws(action, (error) => {
    assert.match(error.message, /Invalid LedgerX development proxy configuration/);
    if (forbiddenText) {
      assert.doesNotMatch(error.message, new RegExp(forbiddenText.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    }
    return true;
  });
}

test('valid loopback origin creates an API proxy that preserves browser-session auth', () => {
  const proxy = createDevProxy({ LEDGERX_DEV_API_ORIGIN: 'http://127.0.0.1:49152/' });

  assert.deepEqual(Object.keys(proxy), ['/api/v1']);
  assert.equal(proxy['/api/v1'].target, 'http://127.0.0.1:49152');
  assert.equal(proxy['/api/v1'].changeOrigin, true);

  const server = new FakeProxyServer();
  proxy['/api/v1'].configure(server);
  const request = new FakeProxyRequest();
  server.emit('proxyReq', request);

  assert.equal(request.getHeader('Authorization'), undefined);
  assert.equal(request.getHeader('Origin'), 'http://127.0.0.1:49152');
});

test('valid loopback origin without token does not write Authorization', () => {
  const proxy = createDevProxy({ LEDGERX_DEV_API_ORIGIN: 'http://127.0.0.1:8080' });
  const server = new FakeProxyServer();
  proxy['/api/v1'].configure(server);
  const request = new FakeProxyRequest({ Authorization: 'stale-value' });
  server.emit('proxyReq', request);

  assert.equal(request.getHeader('Authorization'), undefined);
  assert.equal(request.getHeader('Origin'), 'http://127.0.0.1:8080');
});

test('an empty origin disables the development proxy', () => {
  assert.deepEqual(createDevProxy({}), {});
  assert.deepEqual(createDevProxy({ LEDGERX_DEV_API_ORIGIN: '' }), {});
});

test('legacy Bearer proxy configuration fails without exposing its value', () => {
  const secret = 'legacy-secret-value';
  assertConfigurationError(
    () => createDevProxy({ LEDGERX_DEV_API_ORIGIN: 'http://127.0.0.1:49152', LEDGERX_DEV_API_TOKEN: secret }),
    secret,
  );
});

test('only exact IPv4 loopback origins with valid ports are accepted', () => {
  const invalidOrigins = [
    'https://127.0.0.1:49152',
    'http://localhost:49152',
    'http://[::1]:49152',
    'http://192.168.1.10:49152',
    'http://example.test:49152',
    'http://127.0.0.1',
    'http://127.0.0.1:0',
    'http://127.0.0.1:65536',
    'http://127.0.0.1:49152:user@example.test',
    'http://user:pass@127.0.0.1:49152',
    'http://127.0.0.1:49152/path',
    'http://127.0.0.1:49152?query=1',
    'http://127.0.0.1:49152#fragment',
    ' http://127.0.0.1:49152',
    'http://127.0.0.1:49152 ',
  ];

  for (const origin of invalidOrigins) {
    assertConfigurationError(
      () => createDevProxy({ LEDGERX_DEV_API_ORIGIN: origin }),
    );
  }
});

test('production mode does not inspect or create a development proxy', () => {
  const env = {
    get LEDGERX_DEV_API_ORIGIN() {
      throw new Error('production must not read development origin');
    },
  };

  assert.deepEqual(createDevProxy(env, 'production'), {});
});
