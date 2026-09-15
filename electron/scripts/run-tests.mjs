import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const unit = spawnSync(process.execPath, ['--test', path.join(root, 'tests', 'unit.test.mjs')], {
  cwd: root,
  stdio: 'inherit'
});
if (unit.status !== 0) {
  process.exit(unit.status ?? 1);
}

const playwrightCli = path.join(root, 'node_modules', '@playwright', 'test', 'cli.js');
// Playwright resolves test arguments relative to cwd; an absolute Windows
// path is interpreted as a filter and results in "No tests found".
const e2e = spawnSync(process.execPath, [playwrightCli, 'test', 'tests/electron.spec.mjs', '--reporter=line'], {
  cwd: root,
  stdio: 'inherit'
});
process.exit(e2e.status ?? 1);
