import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

const productionCsp = "'self'";
const developmentCsp = "'self' 'unsafe-inline'";
const loopbackOriginPattern = /^http:\/\/127\.0\.0\.1:(\d{1,5})\/?$/;
const sessionTokenPattern = /^[A-Za-z0-9_-]{43}$/;

const cspPlugin = {
  name: 'ledgerx-csp',
  transformIndexHtml(html, context) {
    const scriptAndStyleSource = context.server ? developmentCsp : productionCsp;
    const connectSource = context.server
      ? "'self' ws: http://127.0.0.1:*"
      : "'self'";
    return html
      .replace('script-src __LEDGERX_CSP__', `script-src ${scriptAndStyleSource}`)
      .replace('style-src __LEDGERX_CSP__', `style-src ${scriptAndStyleSource}`)
      .replace('connect-src __LEDGERX_CSP__', `connect-src ${connectSource}`);
  },
};

function configurationError(reason) {
  return new Error(`Invalid LedgerX development proxy configuration: ${reason}`);
}

function validateLoopbackOrigin(value) {
  if (typeof value !== 'string' || value.length === 0 || value !== value.trim()) {
    throw configurationError('LEDGERX_DEV_API_ORIGIN must be an exact loopback origin');
  }

  const match = loopbackOriginPattern.exec(value);
  if (!match) {
    throw configurationError('LEDGERX_DEV_API_ORIGIN must use http://127.0.0.1 with an explicit port');
  }

  const port = Number(match[1]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw configurationError('LEDGERX_DEV_API_ORIGIN port must be between 1 and 65535');
  }

  // Parsing is retained as a second guard against URL parser edge cases. The
  // strict pattern above already rejects credentials, paths, queries, hashes,
  // alternate hosts and whitespace before this call.
  const parsed = new URL(value);
  if (
    parsed.protocol !== 'http:' ||
    parsed.hostname !== '127.0.0.1' ||
    parsed.username ||
    parsed.password ||
    parsed.search ||
    parsed.hash ||
    (parsed.pathname !== '/' && parsed.pathname !== '')
  ) {
    throw configurationError('LEDGERX_DEV_API_ORIGIN must be a root loopback origin');
  }

  return `http://127.0.0.1:${port}`;
}

function readConfigValue(env, key) {
  const value = env?.[key];
  if (value === undefined || value === null) {
    return '';
  }
  return value;
}

/**
 * Build the Vite development proxy without reading process.env directly.
 * Production callers get an empty proxy and do not inspect token values.
 */
export function createDevProxy(env = {}, mode = 'development') {
  if (mode !== 'development') {
    return {};
  }

  const apiOrigin = readConfigValue(env, 'LEDGERX_DEV_API_ORIGIN');
  const apiToken = readConfigValue(env, 'LEDGERX_DEV_API_TOKEN');

  if (typeof apiOrigin !== 'string' || typeof apiToken !== 'string') {
    throw configurationError('development proxy values must be strings');
  }

  if (apiOrigin === '' && apiToken === '') {
    return {};
  }

  if (apiOrigin === '') {
    throw configurationError('LEDGERX_DEV_API_ORIGIN is required when a token is provided');
  }

  const targetOrigin = validateLoopbackOrigin(apiOrigin);
  if (apiToken !== '' && !sessionTokenPattern.test(apiToken)) {
    throw configurationError('LEDGERX_DEV_API_TOKEN must match the 43-character session-token format');
  }

  return {
    '/api/v1': {
      target: targetOrigin,
      changeOrigin: true,
      configure(proxyServer) {
        proxyServer.on('proxyReq', (proxyRequest) => {
          if (apiToken !== '') {
            proxyRequest.setHeader('Authorization', `Bearer ${apiToken}`);
          } else {
            proxyRequest.removeHeader?.('Authorization');
          }
          proxyRequest.setHeader('Origin', targetOrigin);
        });
      },
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = mode === 'development' ? loadEnv(mode, process.cwd(), '') : {};
  const proxy = createDevProxy(env, mode);

  return {
    plugins: [vue(), cspPlugin],
    server: {
      proxy,
      strictPort: true,
    },
    build: {
      sourcemap: false,
    },
  };
});
