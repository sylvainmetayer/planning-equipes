// Pages CMS may lose a field the configuration does not declare: every key of
// every JSON file must be declared in .pages.yml, in both languages.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';
import { pages, root } from './helpers.mjs';

const config = parse(readFileSync(join(root, '.pages.yml'), 'utf8'));

// Paths declared by a list of fields; a field with `list` is an array, so it also declares its "[]"
const declared = (fields, path = '') =>
  fields.flatMap((f) => {
    const here = `${path}.${f.name}`;
    const item = f.list ? `${here}[]` : here;
    return [here, ...(f.list ? [item] : []), ...(f.fields ? declared(f.fields, item) : [])];
  });

test('every key of every page file is declared in .pages.yml', () => {
  for (const [name, file] of Object.entries(pages)) {
    const entry = config.content.find((c) => c.name === name);
    assert.ok(entry, `${name} : pas de bloc dans .pages.yml`);
    assert.equal(entry.path, `src/_data/pages/${name}.json`);
    const known = new Set(declared(entry.fields));
    const keys = (value, path = '') =>
      Array.isArray(value)
        ? [`${path}[]`, ...value.flatMap((v) => keys(v, `${path}[]`))]
        : value && typeof value === 'object'
          ? Object.entries(value).flatMap(([k, v]) => [`${path}.${k}`, ...keys(v, `${path}.${k}`)])
          : [];
    for (const key of new Set(keys(file))) {
      if (!known.has(key)) assert.fail(`${name}.json : ${key} n'est pas déclaré dans .pages.yml`);
    }
  }
});
