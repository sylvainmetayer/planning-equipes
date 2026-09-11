#!/usr/bin/env node
'use strict';

/*
 * The stores of `core/` are read by the pages and written by themselves.
 *
 * Issue #392 (B9) measured the opposite: seven stores exposed writable
 * signals and two `asReadonly()` existed in the whole project — the one-way
 * flow held by discipline alone. This reads the sources and refuses a public
 * writable signal in `core/`: a state the pages can write is a state nobody
 * owns. Only the `@Injectable` classes are read: a base component such as
 * `reference-table-page.ts` keeps its own page state. The shape it holds is
 * `private readonly _x = signal(…)` paired with
 * `readonly x = this._x.asReadonly()`; a write from outside goes through a
 * method of the store, a fixture through `core/testing/seed-store.ts`.
 *
 *   node scripts/check-readonly-stores.js
 */
const { readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');

const CORE = join(__dirname, '..', 'src', 'app', 'core');
/**
 * A writable signal exposed by a class member, in the four forms the language
 * allows: `x = signal(…)`, `x = linkedSignal(…)` (writable too), an annotated
 * `x: WritableSignal<…> = signal(…)`, and `x: WritableSignal<…>;` assigned in
 * the constructor. The first version of this check read the first form only.
 */
const PUBLIC_SIGNAL =
  /^ {2}(?:readonly |protected |protected readonly |public |public readonly )?\w+(?:\s*:\s*WritableSignal<[^;=]*>\s*;|(?:\s*:\s*WritableSignal<[^;=]*>)?\s*=\s*(?:signal|linkedSignal)[<(])/;
const PRIVATE_SIGNAL =
  /^ {2}private readonly _(\w+)(?:\s*:\s*WritableSignal<[^;=]*>)?\s*=\s*(?:signal|linkedSignal)[<(]/gm;

/** Every .ts of core/ and its sub-folders: core/api/ holds eleven @Injectable classes, the likeliest home of a cache. */
function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if (name.endsWith('.ts') && !name.endsWith('.spec.ts')) out.push(path);
  }
  return out;
}

const offenders = [];
const unpaired = [];
let stores = 0;
for (const file of walk(CORE, []).sort()) {
  const name = relative(CORE, file);
  const source = readFileSync(file, 'utf8');
  // Stores and services only: a base component keeps its own page state.
  if (!source.includes('@Injectable(')) continue;
  source.split('\n').forEach((line, index) => {
    if (PUBLIC_SIGNAL.test(line)) offenders.push(`${name}:${index + 1} ${line.trim()}`);
  });
  let paired = 0;
  for (const [, field] of source.matchAll(PRIVATE_SIGNAL)) {
    if (source.includes(`readonly ${field} = this._${field}.asReadonly();`)) paired++;
    else unpaired.push(`${name} _${field}`);
  }
  if (paired) stores++;
}
if (offenders.length || unpaired.length) {
  if (offenders.length) {
    console.error('check-readonly-stores : signal(s) inscriptible(s) exposé(s) par core/ :');
    for (const o of offenders) console.error('  ' + o);
    console.error(
      '  → `private readonly _x = signal(…)` et `readonly x = this._x.asReadonly()` ; une écriture passe par une méthode du store, une fixture par core/testing/seed-store.ts',
    );
  }
  if (unpaired.length) {
    console.error('check-readonly-stores : signal(s) privé(s) sans vue en lecture seule :');
    for (const u of unpaired) console.error('  ' + u);
  }
  process.exit(1);
}
console.log(
  `check-readonly-stores : ${stores} store(s), aucun signal inscriptible exposé par core/.`,
);
