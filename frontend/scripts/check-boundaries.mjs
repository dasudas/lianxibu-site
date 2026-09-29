import { readFile, readdir } from 'node:fs/promises';
import { join, relative, sep } from 'node:path';

export function violation(relativePath, source) {
  const path = relativePath.split(sep).join('/');
  if (/(^|\/)(data|learning-demo)\.js$/.test(path)) {
    return 'demo data module is forbidden in a production build';
  }
  if (/\b(?:sessionStorage|localStorage)\b/.test(source)) {
    return 'browser storage cannot be used as production business state';
  }
  if (/(^|\/)components\//.test(path)
      && (/\b(?:fetch|XMLHttpRequest)\b/.test(source)
          || /(?:from\s*|import\s*\()['"][^'"]*\/api\//.test(source))) {
    return 'components cannot call or import the API';
  }
  return null;
}

export async function checkSource(sourceRoot) {
  async function walk(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        await walk(path);
      } else if (entry.name.endsWith('.js')) {
        const reason = violation(relative(sourceRoot, path), await readFile(path, 'utf8'));
        if (reason) throw new Error(`${relative(sourceRoot, path)}: ${reason}`);
      }
    }
  }
  await walk(sourceRoot);
}
