import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const electronRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const projectRoot = path.resolve(electronRoot, '..');
const targetRoot = path.join(projectRoot, 'target');
const frontendDist = path.join(projectRoot, 'frontend', 'dist');
const stagingRoot = path.join(electronRoot, 'packaging');
const javaRoot = path.join(stagingRoot, 'java');
const dependencyRoot = path.join(javaRoot, 'lib');
const webRoot = path.join(stagingRoot, 'resources', 'web');

function requirePath(filePath, description, expectedType = 'file') {
  const stat = fs.statSync(filePath, { throwIfNoEntry: false });
  const valid = expectedType === 'directory' ? stat?.isDirectory() : stat?.isFile();
  if (!valid) {
    throw new Error(`${description} is missing: ${filePath}`);
  }
}

const jars = fs.readdirSync(targetRoot)
  .filter((entry) => /^ledgerx-desktop-.+\.jar$/.test(entry))
  .map((entry) => path.join(targetRoot, entry));
if (jars.length !== 1) {
  throw new Error(`expected exactly one application jar in ${targetRoot}, found ${jars.length}`);
}

const classpathFile = path.join(targetRoot, 'cp.txt');
requirePath(classpathFile, 'Maven runtime classpath');
requirePath(frontendDist, 'frontend build directory', 'directory');
requirePath(path.join(frontendDist, 'index.html'), 'frontend build entry');

const dependencies = fs.readFileSync(classpathFile, 'utf8')
  .trim()
  .split(path.delimiter)
  .filter(Boolean);
for (const dependency of dependencies) {
  requirePath(dependency, 'runtime dependency');
}

fs.rmSync(stagingRoot, { recursive: true, force: true });
fs.mkdirSync(dependencyRoot, { recursive: true });
fs.mkdirSync(webRoot, { recursive: true });
fs.copyFileSync(jars[0], path.join(javaRoot, 'ledgerx-desktop.jar'));
for (const dependency of dependencies) {
  fs.copyFileSync(dependency, path.join(dependencyRoot, path.basename(dependency)));
}
fs.cpSync(frontendDist, webRoot, { recursive: true });

console.log(`Prepared Electron resources: ${path.relative(projectRoot, stagingRoot)}`);
