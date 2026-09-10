#!/usr/bin/env node
'use strict';

/*
 * Fails when a page, the shell or a shared component writes an API path.
 *
 * `core/api/` owns the URLs: one service per resource, typed, tested against
 * the paths it calls. A page that writes `'/api/stands/…'` itself is a page
 * that renaming a route has to find by grep — issue #392 counted 108 such
 * literals in 41 files, 143 endpoints, and `api.service.ts` claiming to be
 * "the only place doing HTTP" while only owning the verbs.
 *
 * A ratchet, like the language policy: the files still carrying literals are
 * listed in api-paths-exceptions.json and may only leave it. A listed file
 * that no longer needs the exception fails too, so the list shrinks and never
 * quietly lets a literal back in.
 *
 *   node scripts/check-api-paths.js          # check
 *   node scripts/check-api-paths.js --list   # print the current offenders, to refresh the list
 */
const { readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');

const ROOT = join(__dirname, '..', 'src', 'app');
const SCANNED = ['pages', 'shell', 'shared'];
const EXCEPTIONS = join(__dirname, 'api-paths-exceptions.json');

/** A quoted or template string starting with /api/ — a comment mentioning a path is not a call. */
const LITERAL = /['"`]\/api\//;

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
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
    .filter(({ text }) => LITERAL.test(text) && !text.startsWith('//') && !text.startsWith('*') && !text.startsWith('/*'));
}

const offenders = new Map();
for (const dir of SCANNED) {
  for (const file of walk(join(ROOT, dir), [])) {
    const hits = literalsIn(file);
    if (hits.length > 0) {
      offenders.set(relative(ROOT, file).replace(/\\/g, '/'), hits);
    }
  }
}

if (process.argv.includes('--list')) {
  console.log(JSON.stringify([...offenders.keys()].sort(), null, 2));
  process.exit(0);
}

const allowed = new Set(JSON.parse(readFileSync(EXCEPTIONS, 'utf8')));
let failed = false;

for (const [file, hits] of [...offenders].sort()) {
  if (!allowed.has(file)) {
    failed = true;
    console.error(`check-api-paths : ${file} écrit un chemin d'API — passez par un service de core/api/ :`);
    for (const { line, text } of hits) {
      console.error(`  ${file}:${line}  ${text}`);
    }
  }
}
for (const file of [...allowed].sort()) {
  if (!offenders.has(file)) {
    failed = true;
    console.error(`check-api-paths : ${file} n'écrit plus de chemin d'API — retirez-le de api-paths-exceptions.json`);
  }
}

if (failed) {
  process.exit(1);
}
console.log(`check-api-paths : aucun chemin d'API hors de core/api/ (${allowed.size} fichier(s) encore tolérés).`);
