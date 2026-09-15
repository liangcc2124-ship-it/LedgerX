'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

const ELECTRON_RUNTIME = Boolean(process.versions && process.versions.electron);
const electron = ELECTRON_RUNTIME ? require('electron') : null;

const STARTUP_TIMEOUT_MS = 15_000;
const STATUS_TIMEOUT_MS = 5_000;
const SHUTDOWN_TIMEOUT_MS = 5_000;
const READY_PREFIX = 'LEDGERX_READY ';
const API_MAJOR = '1';
const API_PATH_PREFIX = '/api/v1/';
const TEST_ENV = Object.freeze({
  javaExecutable: 'LEDGERX_TEST_JAVA_EXECUTABLE',
  javaArgs: 'LEDGERX_TEST_JAVA_ARGS_JSON',
  dataDir: 'LEDGERX_TEST_DATA_DIR',
  webRoot: 'LEDGERX_TEST_WEB_ROOT',
  userDataDir: 'LEDGERX_TEST_USER_DATA_DIR'
});

function createSessionToken(randomBytes = crypto.randomBytes(32)) {
  if (!Buffer.isBuffer(randomBytes) || randomBytes.length !== 32) {
    throw new TypeError('session token requires 32 random bytes');
  }
  return randomBytes.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function uuidV4() {
  const bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function parseReadinessLine(line) {
  if (typeof line !== 'string' || !line.startsWith(READY_PREFIX)) {
    return null;
  }
  const payload = line.slice(READY_PREFIX.length).trim();
  if (!payload.startsWith('{') || !payload.endsWith('}')) {
    throw new Error('invalid readiness payload');
  }
  let value;
  try {
    value = JSON.parse(payload);
  } catch (error) {
    throw new Error('invalid readiness json');
  }
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error('readiness must be an object');
  }
  if (!Number.isInteger(value.port) || value.port < 1 || value.port > 65_535 || value.protocol !== '1') {
    throw new Error('readiness port or protocol is invalid');
  }
  return { port: value.port, protocol: value.protocol };
}

function isTargetApiRequest(details, origin) {
  if (!details || typeof details.url !== 'string' || typeof origin !== 'string') {
    return false;
  }
  if (details.resourceType !== 'xhr' && details.resourceType !== 'fetch') {
    return false;
  }
  let requestUrl;
  let expectedOrigin;
  try {
    requestUrl = new URL(details.url);
    expectedOrigin = new URL(origin);
  } catch (error) {
    return false;
  }
  return requestUrl.origin === expectedOrigin.origin
    && requestUrl.pathname.startsWith(API_PATH_PREFIX);
}

function validateStatusPayload(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    return false;
  }
  const data = payload.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return false;
  }
  if (typeof data.apiVersion !== 'string' || data.apiVersion.split('.', 1)[0] !== API_MAJOR) {
    return false;
  }
  if (typeof data.applicationVersion !== 'string' || data.applicationVersion.trim() === '') {
    return false;
  }
  if (!['STARTING', 'READY', 'RECOVERY_REQUIRED'].includes(data.state)) {
    return false;
  }
  if (!Array.isArray(data.capabilities) || !data.capabilities.includes('system.status')) {
    return false;
  }
  if (!Object.prototype.hasOwnProperty.call(payload, 'meta') || !payload.meta || typeof payload.meta !== 'object') {
    return false;
  }
  return true;
}

function sanitizeLog(value, sensitiveValues = []) {
  let text = typeof value === 'string' ? value : JSON.stringify(value);
  if (typeof text !== 'string') {
    text = String(text);
  }
  for (const sensitive of sensitiveValues) {
    if (typeof sensitive === 'string' && sensitive.length > 0) {
      text = text.split(sensitive).join('[redacted]');
    }
  }
  return text.replace(/Bearer\s+[A-Za-z0-9_-]{20,}/gi, 'Bearer [redacted]');
}

function parseJavaArgs(value) {
  if (value == null || value === '') {
    return null;
  }
  let args;
  try {
    args = JSON.parse(value);
  } catch (error) {
    throw new Error('LEDGERX_TEST_JAVA_ARGS_JSON must be a JSON array');
  }
  if (!Array.isArray(args) || args.some((arg) => typeof arg !== 'string')) {
    throw new Error('LEDGERX_TEST_JAVA_ARGS_JSON must contain only strings');
  }
  return args;
}

function resolveJavaExecutable(env = process.env) {
  if (env[TEST_ENV.javaExecutable]) {
    return env[TEST_ENV.javaExecutable];
  }
  if (env.JAVA_HOME) {
    const executable = process.platform === 'win32' ? 'java.exe' : 'java';
    return path.join(env.JAVA_HOME, 'bin', executable);
  }
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function resolveSourceDevelopmentConfig(resourcesRoot, configuredArgs, env = process.env) {
  if (configuredArgs) {
    return null;
  }

  const packagedJar = path.join(resourcesRoot, 'java', 'ledgerx-desktop.jar');
  const projectRoot = path.resolve(__dirname, '..');
  const classesRoot = path.join(projectRoot, 'target', 'classes');
  const classpathFile = path.join(projectRoot, 'target', 'cp.txt');
  const sourceWebRoot = path.join(projectRoot, 'frontend', 'dist');
  let sourceArtifactsReady = false;
  try {
    sourceArtifactsReady = fs.statSync(classesRoot).isDirectory()
      && fs.statSync(classpathFile).isFile()
      && fs.statSync(path.join(sourceWebRoot, 'index.html')).isFile();
  } catch (error) {
    sourceArtifactsReady = false;
  }
  if (fs.existsSync(packagedJar) || !sourceArtifactsReady) {
    return null;
  }

  let dependencyClasspath;
  try {
    dependencyClasspath = fs.readFileSync(classpathFile, 'utf8').trim();
  } catch (error) {
    return null;
  }
  const classpath = [classesRoot, dependencyClasspath].filter(Boolean).join(path.delimiter);
  return {
    args: ['-cp', classpath, 'com.ledgerx.http.HttpServerMain'],
    webRoot: env[TEST_ENV.webRoot] || sourceWebRoot,
    sourceFallback: true
  };
}

function resolveBackendConfig(env = process.env) {
  const configuredArgs = parseJavaArgs(env[TEST_ENV.javaArgs]);
  const resourcesRoot = ELECTRON_RUNTIME ? process.resourcesPath : path.dirname(__dirname);
  const jarPath = path.join(resourcesRoot, 'java', 'ledgerx-desktop.jar');
  const packagedClasspath = [
    jarPath,
    path.join(resourcesRoot, 'java', 'lib', '*')
  ].join(path.delimiter);
  const sourceConfig = resolveSourceDevelopmentConfig(resourcesRoot, configuredArgs, env);
  const args = configuredArgs || sourceConfig?.args || ['-cp', packagedClasspath, 'com.ledgerx.http.HttpServerMain'];
  const webRoot = env[TEST_ENV.webRoot]
    || sourceConfig?.webRoot
    || path.join(resourcesRoot, 'resources', 'web');
  return {
    executable: resolveJavaExecutable(env),
    args,
    // The desktop shell owns the data-root decision.  A generic inherited
    // LEDGERX_DATA_DIR is intentionally ignored; only the isolated test
    // override is accepted here and is validated by resolveDataDir().
    dataDir: env[TEST_ENV.dataDir] || null,
    webRoot,
    sourceFallback: Boolean(sourceConfig)
  };
}

function isAuthorizedSender(event, mainWindow) {
  if (!mainWindow || mainWindow.isDestroyed() || !event || event.sender !== mainWindow.webContents) {
    return false;
  }
  if (event.senderFrame && mainWindow.webContents.mainFrame && event.senderFrame !== mainWindow.webContents.mainFrame) {
    return false;
  }
  return true;
}

function createRuntime() {
  return {
    state: 'CREATED',
    child: null,
    token: null,
    origin: null,
    status: null,
    session: null,
    partition: null,
    window: null,
    dataDir: null,
    startPromise: null,
    stopPromise: null,
    quitRequested: false,
    allowQuit: false,
    stopping: false,
    retrying: false
  };
}

const runtime = createRuntime();

function logEvent(event, details = {}) {
  const sensitive = [runtime.token, runtime.dataDir].filter(Boolean);
  const safe = { timestamp: new Date().toISOString(), component: 'electron-main', event, ...details };
  try {
    process.stderr.write(`${sanitizeLog(safe, sensitive)}\n`);
  } catch (error) {
    // Logging must never affect lifecycle handling.
  }
}

function requestStatus(origin, token, timeoutMs = STATUS_TIMEOUT_MS) {
  const target = new URL('/api/v1/system/status', origin);
  const requestId = uuidV4();
  return new Promise((resolve, reject) => {
    const request = http.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method: 'GET',
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${token}`,
        'X-Request-Id': requestId
      },
      timeout: timeoutMs
    }, (response) => {
      const chunks = [];
      let size = 0;
      response.on('data', (chunk) => {
        size += chunk.length;
        if (size <= 1024 * 1024) {
          chunks.push(chunk);
        }
      });
      response.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        if (size > 1024 * 1024 || response.statusCode !== 200) {
          reject(new Error('status handshake failed'));
          return;
        }
        let payload;
        try {
          payload = JSON.parse(body);
        } catch (error) {
          reject(new Error('status handshake returned invalid json'));
          return;
        }
        if (!validateStatusPayload(payload)) {
          reject(new Error('status handshake returned incompatible data'));
          return;
        }
        resolve(payload);
      });
    });
    request.on('timeout', () => request.destroy(new Error('status handshake timeout')));
    request.on('error', () => reject(new Error('status handshake unavailable')));
    request.end();
  });
}

function installSessionHeaderHook(session, origin, token) {
  const filter = { urls: [`${origin}${API_PATH_PREFIX}*`] };
  session.webRequest.onBeforeSendHeaders(filter, (details, callback) => {
    const requestHeaders = { ...(details.requestHeaders || {}) };
    if (isTargetApiRequest(details, origin)) {
      requestHeaders.Authorization = `Bearer ${token}`;
    }
    callback({ requestHeaders });
  });
}

function installSessionSecurity(session) {
  session.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
  session.setPermissionCheckHandler(() => false);
}

function clearSession(session) {
  if (!session || !session.webRequest || typeof session.webRequest.onBeforeSendHeaders !== 'function') {
    return;
  }
  session.webRequest.onBeforeSendHeaders(null);
}

function createBrowserWindow() {
  if (runtime.window && !runtime.window.isDestroyed()) {
    return runtime.window;
  }
  runtime.window = new electron.BrowserWindow({
    show: false,
    width: 1200,
    height: 800,
    minWidth: 320,
    minHeight: 200,
    backgroundColor: '#f7f8fa',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      devTools: false,
      session: runtime.session || undefined,
      partition: runtime.partition || undefined
    }
  });
  runtime.window.once('ready-to-show', () => {
    if (runtime.window && !runtime.window.isDestroyed()) {
      runtime.window.show();
    }
  });
  runtime.window.webContents.on('will-navigate', (event, url) => {
    if (url === pathToErrorFileUrl() || (runtime.origin && url.startsWith(`${runtime.origin}/`))) {
      return;
    }
    event.preventDefault();
  });
  runtime.window.webContents.on('render-process-gone', (_event, details) => {
    logEvent('renderer.gone', {
      reason: details && details.reason,
      exitCode: details && details.exitCode
    });
  });
  runtime.window.webContents.on('crashed', () => {
    logEvent('renderer.crashed');
  });
  runtime.window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  runtime.window.on('closed', () => {
    runtime.window = null;
  });
  return runtime.window;
}

function pathToErrorFile() {
  return path.join(__dirname, 'error.html');
}

function pathToErrorFileUrl() {
  return require('node:url').pathToFileURL(pathToErrorFile()).toString();
}

async function showErrorPage() {
  if (!ELECTRON_RUNTIME) {
    return;
  }
  const window = createBrowserWindow();
  if (!window.isDestroyed()) {
    try {
      await window.loadFile(pathToErrorFile());
    } catch (error) {
      logEvent('error_page_failed');
    }
  }
}

function waitForExit(child, timeoutMs) {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    let settled = false;
    const finish = () => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        resolve();
      }
    };
    const timer = setTimeout(finish, timeoutMs);
    child.once('exit', finish);
    child.once('close', finish);
  });
}

async function stopBackend() {
  if (runtime.stopPromise) {
    return runtime.stopPromise;
  }
  runtime.stopPromise = (async () => {
    runtime.stopping = true;
    const child = runtime.child;
    if (child) {
      try {
        if (child.stdin && child.stdin.writable) {
          child.stdin.write('LEDGERX_STOP\n');
          child.stdin.end();
        }
      } catch (error) {
        logEvent('backend_stop_stdin_failed');
      }
      await waitForExit(child, SHUTDOWN_TIMEOUT_MS);
      if (child.exitCode === null && child.signalCode === null) {
        try {
          child.kill();
        } catch (error) {
          logEvent('backend_force_stop_failed');
        }
        await waitForExit(child, 1_000);
      }
    }
      runtime.child = null;
    runtime.origin = null;
    runtime.status = null;
    clearSession(runtime.session);
    runtime.session = null;
    runtime.partition = null;
    runtime.stopping = false;
  })().finally(() => {
    runtime.stopPromise = null;
  });
  return runtime.stopPromise;
}

function spawnBackend(config, token) {
  if (!config || typeof config.dataDir !== 'string' || !path.isAbsolute(config.dataDir)) {
    throw new Error('effective data directory is required');
  }
  const childEnv = { ...process.env };
  childEnv.LEDGERX_SESSION_TOKEN = token;
  childEnv.LEDGERX_PARENT_PID = String(process.pid);
  // Do not let a generic parent-process value select the user's database.
  // The resolved value is the single source of truth for this session.
  delete childEnv.LEDGERX_DATA_DIR;
  childEnv.LEDGERX_DATA_DIR = config.dataDir;
  if (config.webRoot) {
    childEnv.LEDGERX_WEB_ROOT = config.webRoot;
  }
  const child = spawn(config.executable, config.args, {
    shell: false,
    windowsHide: true,
    stdio: ['pipe', 'pipe', 'pipe'],
    env: childEnv
  });
  runtime.child = child;
  logEvent('backend.spawned', { pid: child.pid });
  return new Promise((resolve, reject) => {
    let settled = false;
    let buffer = '';
    const finish = (error, value) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      if (error) {
        reject(error);
      } else {
        resolve(value);
      }
    };
    const timer = setTimeout(() => finish(new Error('backend readiness timeout')), STARTUP_TIMEOUT_MS);
    const consume = (chunk) => {
      buffer += chunk.toString('utf8');
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith(READY_PREFIX)) {
          continue;
        }
        try {
          const readiness = parseReadinessLine(line);
          if (readiness) {
            finish(null, readiness);
            return;
          }
        } catch (error) {
          finish(error);
          return;
        }
      }
    };
    child.stdout.on('data', consume);
    child.stderr.on('data', (chunk) => {
      logEvent('backend.stderr', { message: sanitizeLog(chunk.toString('utf8'), [token, config.dataDir, config.webRoot]) });
    });
    child.once('error', () => finish(new Error('backend spawn failed')));
    child.once('exit', () => {
      if (!settled) {
        finish(new Error('backend exited before readiness'));
      }
      if (!runtime.stopping && runtime.state === 'UI_ACTIVE') {
        runtime.state = 'BACKEND_LOST';
        logEvent('backend.exited', { pid: child.pid });
        void showErrorPage();
      }
    });
  });
}

function resolveDataDir(config = {}, env = process.env) {
  const configuredDataDir = config && config.dataDir;
  if (configuredDataDir != null && configuredDataDir !== '') {
    if (typeof configuredDataDir !== 'string' || !path.isAbsolute(configuredDataDir)) {
      throw new Error('test data directory must be absolute');
    }
    return path.normalize(configuredDataDir);
  }

  const localAppData = env && env.LOCALAPPDATA;
  if (typeof localAppData !== 'string' || localAppData.trim() === '' || !path.isAbsolute(localAppData)) {
    throw new Error('LOCALAPPDATA is unavailable');
  }
  return path.join(path.normalize(localAppData), 'LedgerX');
}

async function startDesktop() {
  if (runtime.startPromise) {
    return runtime.startPromise;
  }
  runtime.startPromise = (async () => {
    runtime.state = 'STARTING_BACKEND';
    runtime.token = createSessionToken();
    const startupStartedAt = Date.now();
    try {
      const config = resolveBackendConfig();
      runtime.dataDir = resolveDataDir(config);
      const effectiveConfig = { ...config, dataDir: runtime.dataDir };
      if (config.sourceFallback) {
        logEvent('backend.source_fallback');
      }
      logEvent('desktop.start', { durationMs: Date.now() - startupStartedAt });
      const readiness = await spawnBackend(effectiveConfig, runtime.token);
      runtime.origin = `http://127.0.0.1:${readiness.port}`;
      logEvent('backend.listening', {
        pid: runtime.child && runtime.child.pid,
        port: readiness.port,
        durationMs: Date.now() - startupStartedAt
      });
      runtime.partition = `temp:ledgerx-${crypto.randomBytes(12).toString('hex')}`;
      runtime.session = electron.session.fromPartition(runtime.partition, { cache: false });
      installSessionSecurity(runtime.session);
      installSessionHeaderHook(runtime.session, runtime.origin, runtime.token);
      runtime.state = 'CHECKING_STATUS';
      runtime.status = await requestStatus(runtime.origin, runtime.token);
      logEvent('backend.status_checked', {
        pid: runtime.child && runtime.child.pid,
        port: readiness.port,
        state: runtime.status.data.state,
        durationMs: Date.now() - startupStartedAt
      });
      runtime.state = 'LOADING_UI';
      const window = createBrowserWindow();
      await window.loadURL(`${runtime.origin}/`);
      runtime.state = 'UI_ACTIVE';
      logEvent('renderer.loaded', {
        pid: runtime.child && runtime.child.pid,
        port: readiness.port,
        state: runtime.status.data.state,
        durationMs: Date.now() - startupStartedAt
      });
      return runtime.status;
    } catch (error) {
      runtime.state = 'FAILED_STARTUP';
      logEvent('backend.start_failed', { reason: sanitizeLog(error.message), durationMs: Date.now() - startupStartedAt });
      await stopBackend();
      runtime.token = null;
      await showErrorPage();
      return null;
    } finally {
      runtime.startPromise = null;
    }
  })();
  return runtime.startPromise;
}

async function retryBackend() {
  if (!['FAILED_STARTUP', 'BACKEND_LOST'].includes(runtime.state)) {
    return { ok: false, error: { code: 'RETRY_NOT_AVAILABLE', message: '当前状态不支持重试。' } };
  }
  runtime.retrying = true;
  try {
    logEvent('backend.retry_requested');
    if (runtime.window && !runtime.window.isDestroyed()) {
      runtime.window.destroy();
      runtime.window = null;
    }
    await stopBackend();
    runtime.token = null;
    runtime.dataDir = null;
    const status = await startDesktop();
    return status ? { ok: true } : { ok: false, error: { code: 'BACKEND_START_FAILED', message: '本地服务启动失败。' } };
  } finally {
    runtime.retrying = false;
  }
}

async function openFixedFolder(kind) {
  const folder = kind === 'data' ? runtime.dataDir : electron.app.getPath('logs');
  if (!folder) {
    return { ok: false, error: { code: 'FOLDER_UNAVAILABLE', message: '目录暂不可用。' } };
  }
  try {
    fs.mkdirSync(folder, { recursive: true });
    const result = await electron.shell.openPath(folder);
    return result ? { ok: false, error: { code: 'OPEN_FOLDER_FAILED', message: '无法打开目录。' } } : { ok: true };
  } catch (error) {
    return { ok: false, error: { code: 'OPEN_FOLDER_FAILED', message: '无法打开目录。' } };
  }
}

function registerIpcHandlers() {
  electron.ipcMain.handle('desktop.openDataFolder', (event) => {
    if (!isAuthorizedSender(event, runtime.window)) {
      return { ok: false, error: { code: 'UNAUTHORIZED_SENDER', message: '调用方未获授权。' } };
    }
    return openFixedFolder('data');
  });
  electron.ipcMain.handle('desktop.openLogsFolder', (event) => {
    if (!isAuthorizedSender(event, runtime.window)) {
      return { ok: false, error: { code: 'UNAUTHORIZED_SENDER', message: '调用方未获授权。' } };
    }
    return openFixedFolder('logs');
  });
  electron.ipcMain.handle('desktop.retryBackend', async (event) => {
    if (!isAuthorizedSender(event, runtime.window)) {
      logEvent('ipc.rejected', { channel: 'desktop.retryBackend' });
      return { ok: false, error: { code: 'UNAUTHORIZED_SENDER', message: '调用方未获授权。' } };
    }
    return retryBackend();
  });
}

function configureTestPaths(env = process.env) {
  if (ELECTRON_RUNTIME && env[TEST_ENV.userDataDir]) {
    fs.mkdirSync(env[TEST_ENV.userDataDir], { recursive: true });
    electron.app.setPath('userData', env[TEST_ENV.userDataDir]);
  }
}

function configureElectronRuntime() {
  // CI and some desktop environments expose no usable GPU process. Disable
  // hardware acceleration before app readiness so the renderer can still
  // exercise the real Electron -> Java path deterministically.
  electron.app.disableHardwareAcceleration();
  electron.app.commandLine.appendSwitch('disable-gpu');
  electron.app.commandLine.appendSwitch('in-process-gpu');
}

function installLifecycle() {
  configureTestPaths();
  const gotLock = electron.app.requestSingleInstanceLock();
  if (!gotLock) {
    electron.app.quit();
    return false;
  }
  electron.app.on('second-instance', () => {
    if (runtime.window && !runtime.window.isDestroyed()) {
      if (runtime.window.isMinimized()) {
        runtime.window.restore();
      }
      runtime.window.show();
      runtime.window.focus();
    }
  });
  electron.app.on('before-quit', (event) => {
    if (runtime.allowQuit) {
      return;
    }
    event.preventDefault();
    if (runtime.quitRequested) {
      return;
    }
    runtime.quitRequested = true;
    runtime.state = 'STOPPING_BACKEND';
    logEvent('desktop.stopping');
    void stopBackend().finally(() => {
      runtime.allowQuit = true;
      electron.app.quit();
    });
  });
  electron.app.on('window-all-closed', () => {
    if (process.platform !== 'darwin' && !runtime.retrying) {
      electron.app.quit();
    }
  });
  electron.app.whenReady().then(() => {
    registerIpcHandlers();
    void startDesktop();
  }).catch(() => {
    runtime.state = 'FAILED_STARTUP';
    void showErrorPage();
  });
  return true;
}

if (ELECTRON_RUNTIME) {
  configureElectronRuntime();
  installLifecycle();
}

module.exports = {
  API_MAJOR,
  API_PATH_PREFIX,
  READY_PREFIX,
  SHUTDOWN_TIMEOUT_MS,
  STARTUP_TIMEOUT_MS,
  createSessionToken,
  createRuntime,
  isAuthorizedSender,
  isTargetApiRequest,
  parseJavaArgs,
  parseReadinessLine,
  resolveBackendConfig,
  resolveDataDir,
  sanitizeLog,
  validateStatusPayload,
  uuidV4
};
