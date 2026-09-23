#!/usr/bin/env node
'use strict';

/*
 * Every `<th>` says which cells it heads, with `scope`.
 *
 * The HTML association algorithm covers the simple table and most screen
 * readers cope without it — but the repository's convention is explicit
 * (docs/developpement.md § Accessibilité, RGAA 5.7), and the one place it was
 * broken is invisible from the template: `MatHeaderCell` sets
 * `role="columnheader"` and never writes a `scope`, so the 106 `<th
 * mat-header-cell>` of the audit of issue #44 carried none. The ESLint rule
 * `@angular-eslint/template/table-scope` does not cover this: it refuses a
 * `scope` on something other than a `<th>`, it does not require one.
 *
 *   node scripts/check-table-headers.js
 */
const { readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');

const APP = join(__dirname, '..', 'src', 'app');
/** Opening `<th>` tags, multi-line ones included; quoted values skipped whole. */
const TH = /<th\b((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
const SCOPE = /\bscope\s*=|\[attr\.scope\]/;

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if ((name.endsWith('.html') || name.endsWith('.ts')) && !name.endsWith('.spec.ts'))
      out.push(path);
  }
  return out;
}

const sansScope = [];
let entetes = 0;
for (const file of walk(APP, []).sort()) {
  const source = readFileSync(file, 'utf8');
  for (const match of source.matchAll(TH)) {
    entetes++;
    if (!SCOPE.test(match[1])) {
      sansScope.push(`${relative(APP, file)}:${source.slice(0, match.index).split('\n').length}`);
    }
  }
}

// A scan that recognises nothing passes green for ever.
if (entetes < 100) {
  console.error(
    `check-table-headers : ${entetes} <th> seulement trouvé(s) — le scan ne lit plus les gabarits.`,
  );
  process.exit(1);
}
if (sansScope.length) {
  console.error('check-table-headers : <th> sans scope :');
  for (const s of sansScope) console.error('  ' + s);
  console.error(
    '  → `scope="col"` sur un en-tête de colonne (un `<th mat-header-cell>` compris : Material ne l’écrit pas), `scope="row"` sur un en-tête de ligne',
  );
  process.exit(1);
}
console.log(`check-table-headers : ${entetes} <th>, tous porteurs d'un scope.`);
