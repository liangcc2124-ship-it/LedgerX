import { spawn, spawnSync } from 'node:child_process';
import net from 'node:net';

const host = '127.0.0.1';
const port = 4173;
const cwd = process.cwd();

function startPreview() {
  return spawn(process.execPath, [
    'node_modules/vite/bin/vite.js',
    'preview',
    '--host',
    host,
    '--port',
    String(port),
  ], { cwd, stdio: ['ignore', 'inherit', 'inherit'] });
}

function waitForPort(server, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    let timer;
    const check = () => {
      if (Date.now() >= deadline) {
        reject(new Error(`preview server did not start on ${host}:${port}`));
        return;
      }
      const socket = net.createConnection({ host, port });
      socket.once('connect', () => {
        socket.destroy();
        resolve();
      });
      socket.once('error', () => {
        socket.destroy();
        timer = setTimeout(check, 100);
      });
    };
    server.once('exit', (code) => reject(new Error(`preview server exited before tests: ${code}`)));
    check();
  });
}

function runPlaywright() {
  const result = spawnSync(process.execPath, ['node_modules/@playwright/test/cli.js', 'test'], {
    cwd,
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (result.error) {
    throw result.error;
  }
  return result.status ?? 1;
}

function stopPreview(server) {
  if (!server || server.exitCode !== null) {
    return;
  }
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(server.pid), '/t', '/f'], { stdio: 'ignore' });
  } else {
    server.kill('SIGTERM');
  }
}

const preview = startPreview();
let exitCode = 1;
try {
  await waitForPort(preview);
  exitCode = await runPlaywright();
} finally {
  stopPreview(preview);
}
process.exit(exitCode);
