import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { checkSource } from './check-boundaries.mjs';

const projectRoot = resolve(import.meta.dirname, '..');
const sourceRoot = join(projectRoot, 'src');
const outputRoot = join(projectRoot, 'dist');

await checkSource(sourceRoot);
await rm(outputRoot, { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });
await cp(sourceRoot, join(outputRoot, 'src'), { recursive: true });
await writeFile(join(outputRoot, 'index.html'), await readFile(join(projectRoot, 'index.html')));
