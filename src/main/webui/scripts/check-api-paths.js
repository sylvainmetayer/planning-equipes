#!/usr/bin/env node
'use strict';

/*
 * Fails when a page, the shell, a shared component or a service of `core/`
 * writes an API path.
 *
 * `core/api/` owns the URLs: one service per resource, typed, tested against
 * the paths it calls. A page that writes `'/api/stands/…'` itself is a page
 * that renaming a route has to find by grep — issue #392 counted 108 such
 * literals in 41 files, 143 endpoints, and `api.service.ts` claiming to be
 * "the only place doing HTTP" while only owning the verbs.
 *
 * A ratchet, like the language policy: a file still carrying literals is
 * listed in api-paths-exceptions.json and can only leave it. A listed file
 * that no longer needs the exception fails too, so the list never quietly lets
 * a literal back in. The pages, the shell and the shared components reached
 * zero with the second half of B3; `core/` itself was outside the scan while
 * AGENTS.md said the opposite, and 82 literals lived there — four of them a
 * path a `core/api/` service already owned. Only the three files that handle
 * the prefix itself (`api.service.ts`, the two interceptors) and `core/api/`
 * are exempt.
 *
 *   node scripts/check-api-paths.js          # check
 *   node scripts/check-api-paths.js --list   # print the current offenders, to refresh the list
 */
const { readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');
const { byCodeUnit } = require('./code-unit-order');

const ROOT = join(__dirname, '..', 'src', 'app');
const SCANNED = ['pages', 'shell', 'shared', 'core'];
const EXCEPTIONS = join(__dirname, 'api-paths-exceptions.json');
/** `core/api/` owns the paths; these three own the prefix (base URL, login redirect, edition header). */
const EXEMPT = new Set(
  [
    'core/api',
    'core/api.service.ts',
    'core/auth.interceptor.ts',
    'core/edition.interceptor.ts',
  ].map((p) => join(ROOT, p)),
);

/**
 * A string starting with /api, quoted or after a `${…}` in a template — a
 * comment mentioning a path is not a call. The prefix alone, not `/api/`: a
 * page declaring `const RACINE = '/api'` and calling `${RACINE}/stands` had
 * moved nothing and passed.
 */
const LITERAL = /(['"`]|\})\/api\b/;

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (EXEMPT.has(path)) {
      continue;
    }
    if (statSync(path).isDirectory()) {
      walk(path, out);
    } else if (name.endsWith('.ts') && !name.endsWith('.spec.ts')) {
      out.push(path);
    }
  }
  return out;
}

function literalsIn(file) {
  return readFileSync(file, 'utf8')
    .split('\n')
    .map((line, index) => ({ line: index + 1, text: line.trim() }))
    .filter(
      ({ text }) =>
        LITERAL.test(text) &&
        !text.startsWith('//') &&
        !text.startsWith('*') &&
        !text.startsWith('/*'),
    );
}

const offenders = new Map();
for (const dir of SCANNED) {
  for (const file of walk(join(ROOT, dir), [])) {
    const hits = literalsIn(file);
    if (hits.length > 0) {
      offenders.set(relative(ROOT, file).replaceAll('\\', '/'), hits);
    }
  }
}

if (process.argv.includes('--list')) {
  console.log(JSON.stringify([...offenders.keys()].sort(byCodeUnit), null, 2));
  process.exit(0);
}

const allowed = new Set(JSON.parse(readFileSync(EXCEPTIONS, 'utf8')));
let failed = false;

for (const [file, hits] of [...offenders].sort(([a], [b]) => byCodeUnit(a, b))) {
  if (!allowed.has(file)) {
    failed = true;
    console.error(
      `check-api-paths : ${file} écrit un chemin d'API — passez par un service de core/api/ :`,
    );
    for (const { line, text } of hits) {
      console.error(`  ${file}:${line}  ${text}`);
    }
  }
}
for (const file of [...allowed].sort(byCodeUnit)) {
  if (!offenders.has(file)) {
    failed = true;
    console.error(
      `check-api-paths : ${file} n'écrit plus de chemin d'API — retirez-le de api-paths-exceptions.json`,
    );
  }
}

if (failed) {
  process.exit(1);
}
console.log(
  `check-api-paths : aucun chemin d'API hors de core/api/ (${allowed.size} fichier(s) encore tolérés).`,
);
