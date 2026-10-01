#!/usr/bin/env node
'use strict';

/*
 * The casts of the specs that silence the compiler — `as unknown as`,
 * `as any`, `as never` — can only go down.
 *
 * Audit #392 (B8) counted them: 107, then 114 a few days later — the price
 * of the fake services a page spec builds by hand, `{ save: vi.fn() }` read
 * back through `as unknown as [string, Item]`, a shape the spec declares and
 * the compiler never holds against the service. The rule is in
 * `src/main/webui/AGENTS.md` (tests): a page spec fakes the `core/` services
 * through `core/testing/fake.ts`, typed on the class, and reaches a
 * component's protected member as `page['member']`, type-checked. This holds
 * the count, file by file, against `scripts/unknown-casts-baseline.json`:
 *
 *   - a spec above its line fails — a spec absent from the baseline has 0;
 *   - a spec below its line fails too, asking for the line to be lowered, so
 *     the baseline only ever records progress and never leaves room for a
 *     cast to come back unnoticed;
 *   - `--update` rewrites the baseline from the specs as they are, and
 *     refuses to raise the total — except when there is no baseline at all,
 *     which it then writes for the first time. A line may rise on its own as
 *     long as the total does not: a spec renamed or moved, or a test carried
 *     from one spec to another, takes its casts along, and the old path is
 *     then below its line while the new one is above it. Refusing that would
 *     leave no way through but removing every cast in the same commit; the
 *     casts moved, they did not multiply. The check itself still fails on
 *     such a rise until `--update` records it, so a move is always a
 *     baseline change somebody commits.
 *
 * The three spellings are one debt: `as any` turns the checking off where
 * `as unknown as` hides it, `as never` is assignable to every type, and none
 * of the three is held by the linter in a spec (`no-explicit-any` is off for
 * `*.spec.ts`, eslint.config.js). Counting only the first would move the
 * casts to the other two instead of removing them.
 *
 * Counted in the whole text of every `*.spec.ts` under `src/`, comments
 * included, the words possibly on two lines and whole words only — neither
 * `has never` nor `as anything` is one — and a cast once: `as unknown as
 * never` is one `as unknown as`.
 *
 *   node scripts/check-unknown-casts.js [--update]
 */
const { readdirSync, readFileSync, statSync, writeFileSync } = require('node:fs');
const { join, relative, sep } = require('node:path');
const { byCodeUnit } = require('./code-unit-order');

const WEBUI = join(__dirname, '..');
const SRC = join(WEBUI, 'src');
const BASELINE = join(__dirname, 'unknown-casts-baseline.json');
const CAST = /\bas\s+(?:unknown\s+as|any|never)\b/g;
/** How the messages name what is counted. */
const CASTS = '« as unknown as », « as any », « as never »';

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if (name.endsWith('.spec.ts')) out.push(path);
  }
  return out;
}

/** Spec path relative to `src/main/webui`, `/`-separated → its count; specs with none are left out. */
function count() {
  const counts = {};
  for (const file of walk(SRC, [])) {
    const hits = (readFileSync(file, 'utf8').match(CAST) ?? []).length;
    if (hits > 0) counts[relative(WEBUI, file).split(sep).join('/')] = hits;
  }
  return counts;
}

/** The committed counts; `null` when the file does not exist yet. */
function readBaseline() {
  try {
    return JSON.parse(readFileSync(BASELINE, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

const total = (counts) => Object.values(counts).reduce((sum, n) => sum + n, 0);

const actual = count();
const update = process.argv.includes('--update');
const recorded = readBaseline();
if (recorded === null && !update) {
  console.error(
    'check-unknown-casts : pas de baseline (scripts/unknown-casts-baseline.json) — ' +
      'npm run unknown-casts-check -- --update pour la créer',
  );
  process.exit(1);
}
// A first --update records the specs as they are: there is no line yet to raise.
const baseline = recorded ?? actual;
const files = [...new Set([...Object.keys(actual), ...Object.keys(baseline)])].sort(byCodeUnit);
const above = files.filter((file) => (actual[file] ?? 0) > (baseline[file] ?? 0));
const below = files.filter((file) => (actual[file] ?? 0) < (baseline[file] ?? 0));
const line = (file) => `  ${file} : ${actual[file] ?? 0} (référence ${baseline[file] ?? 0})`;

// A rise of the total is a cast added; a rise of one spec within an unchanged
// total is a cast moved (a spec renamed, a test carried over), which --update
// records. The check alone refuses both, until the baseline says so.
const totalRises = total(actual) > total(baseline);
if (above.length && (!update || totalRises)) {
  console.error(`check-unknown-casts : casts (${CASTS}) en hausse dans les specs :`);
  for (const file of above) console.error(line(file));
  console.error(
    '  → un faux service typé (core/testing/fake.ts : fakeOf, provideFake) plutôt qu’un cast ; ' +
      "un membre protégé du composant se lit `page['membre']`, typé",
  );
  if (update) {
    console.error(
      `  --update ne relève jamais le total (${total(actual)} contre ${total(baseline)}) : ` +
        'la baseline reste inchangée.',
    );
  } else if (!totalRises) {
    console.error(
      `  le total ne monte pas (${total(actual)} contre ${total(baseline)}) : si ces casts ont ` +
        'seulement changé de spec (renommée, déplacée), npm run unknown-casts-check -- --update ' +
        "l'enregistre.",
    );
  }
  process.exit(1);
}

if (update) {
  const sorted = {};
  for (const file of Object.keys(actual).sort(byCodeUnit)) sorted[file] = actual[file];
  writeFileSync(BASELINE, JSON.stringify(sorted, null, 2) + '\n');
  console.log(
    `check-unknown-casts : baseline réécrite — ${total(sorted)} casts (${CASTS}) ` +
      `dans ${Object.keys(sorted).length} spec(s) ` +
      (recorded === null ? '(première baseline).' : `(${total(recorded)} auparavant).`),
  );
  if (above.length) {
    console.log('  casts déplacés d’une spec à l’autre, total inchangé ou en baisse :');
    for (const file of above) console.log(line(file));
  }
  process.exit(0);
}

if (below.length) {
  console.error(
    `check-unknown-casts : casts (${CASTS}) en baisse : abaissez la référence de ces specs :`,
  );
  for (const file of below) console.error(line(file));
  console.error('  → npm run unknown-casts-check -- --update, et committez la baseline');
  process.exit(1);
}

console.log(
  `check-unknown-casts : ${total(actual)} casts (${CASTS}) dans ${Object.keys(actual).length} ` +
    'spec(s), aucun au-dessus de sa référence.',
);
