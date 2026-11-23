import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = fileURLToPath(new URL('..', import.meta.url));
export const catalogPath = join(root, '..', 'src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java');
export const pagesDir = join(root, 'src/_data/pages');
export const pages = Object.fromEntries(
  readdirSync(pagesDir).map((f) => [f.replace(/\.json$/, ''), JSON.parse(readFileSync(join(pagesDir, f), 'utf8'))]),
);

// Every string of a JSON value, with its path
export function* strings(value, path = '') {
  if (typeof value === 'string') yield [path, value];
  else if (Array.isArray(value)) for (const [i, v] of value.entries()) yield* strings(v, `${path}[${i}]`);
  else if (value && typeof value === 'object') for (const [k, v] of Object.entries(value)) yield* strings(v, `${path}.${k}`);
}

// Every path of a JSON value, arrays collapsed to [] (the shape of the data)
export function shape(value, path = '') {
  if (Array.isArray(value)) return [`${path}[]`, ...value.flatMap((v) => shape(v, `${path}[]`))];
  if (value && typeof value === 'object') return Object.entries(value).flatMap(([k, v]) => [`${path}.${k}`, ...shape(v, `${path}.${k}`)]);
  return [];
}

export function files(dir, ext) {
  return readdirSync(dir, { recursive: true }).filter((f) => f.endsWith(ext) && statSync(join(dir, f)).isFile()).map((f) => join(dir, f));
}
