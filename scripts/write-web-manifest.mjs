import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const webRoot = path.resolve(process.argv[2] || path.join(projectRoot, 'frontend', 'dist'));
const entries = [];

async function collect(directory, relativeDirectory = '') {
  const items = await fs.readdir(directory, { withFileTypes: true });
  items.sort((left, right) => left.name.localeCompare(right.name, 'en'));
  for (const item of items) {
    const relative = relativeDirectory ? `${relativeDirectory}/${item.name}` : item.name;
    const absolute = path.join(directory, item.name);
    if (item.isSymbolicLink()) throw new Error(`Web build contains a symbolic link: ${relative}`);
    if (item.isDirectory()) {
      await collect(absolute, relative);
      continue;
    }
    if (!item.isFile()) throw new Error(`Web build contains a non-file entry: ${relative}`);
    if (relative !== 'index.html' && !relative.startsWith('assets/')) {
      throw new Error(`Web build contains an unsupported file: ${relative}`);
    }
    if (!/^[A-Za-z0-9._/-]+$/.test(relative) || relative.includes('..')) {
      throw new Error(`Web build contains an unsafe file name: ${relative}`);
    }
    if (!/\.(html|js|css|json|svg)$/i.test(relative)) {
      throw new Error(`Web build contains an unsupported resource type: ${relative}`);
    }
    entries.push(relative);
  }
}

await collect(webRoot);
if (!entries.includes('index.html')) throw new Error('Web build is missing index.html.');
const manifest = `${entries.sort().map((entry) => `${entry}=${entry}`).join('\n')}\n`;
await fs.writeFile(path.join(webRoot, 'manifest.properties'), manifest, 'utf8');
