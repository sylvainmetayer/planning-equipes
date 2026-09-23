#!/usr/bin/env node
'use strict';

/*
 * A link that opens a new window says so (RGAA 13.2).
 *
 * The audit of issue #45 found 31 `target="_blank"` and not one announcing
 * itself: a person who does not see the window open finds « Précédent »
 * leading nowhere, and nothing said the context changed. `shared/new-window-link.ts`
 * is the fix — a directive on `a[target="_blank"]` that appends the notice as
 * visually hidden text — and a directive only runs where it is imported. So:
 *
 * - every component whose template holds such a link imports `NewWindowLink`;
 * - except where the link is named by an `aria-label`, which overrides the
 *   content the notice is appended to: that label is bound through
 *   `newWindowLabel(…)` instead — a literal `aria-label` is refused, since it
 *   cannot carry the translated notice.
 *
 *   node scripts/check-new-window-links.js
 */
const { existsSync, readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');

const APP = join(__dirname, '..', 'src', 'app');
const LIEN = /<a\b((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
const NOUVELLE_FENETRE = /\btarget\s*=\s*"_blank"/;

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if ((name.endsWith('.html') || name.endsWith('.ts')) && !name.endsWith('.spec.ts'))
      out.push(path);
  }
  return out;
}

/** The component source a template belongs to: itself for an inline one. */
function composantDe(file) {
  if (file.endsWith('.ts')) return file;
  const ts = file.replace(/\.html$/, '.ts');
  return existsSync(ts) ? ts : null;
}

const sansDirective = new Set();
const libellesMuets = [];
let liens = 0;
for (const file of walk(APP, []).sort()) {
  if (file.endsWith('new-window-link.ts')) continue;
  const source = readFileSync(file, 'utf8');
  for (const match of source.matchAll(LIEN)) {
    const attributs = match[1];
    if (!NOUVELLE_FENETRE.test(attributs)) continue;
    liens++;
    const where = `${relative(APP, file)}:${source.slice(0, match.index).split('\n').length}`;
    const composant = composantDe(file);
    const code = composant ? readFileSync(composant, 'utf8') : '';
    if (/(^|\s)aria-label\s*=/.test(attributs)) {
      libellesMuets.push(`${where} aria-label littéral`);
    } else if (/\[attr\.aria-label\]/.test(attributs)) {
      // Named by its label, which overrides the content the directive would
      // append to: the label carries the notice, and the directive is not needed.
      if (!code.includes('newWindowLabel(')) {
        libellesMuets.push(`${where} [attr.aria-label] sans newWindowLabel(…)`);
      }
    } else if (!/imports:\s*\[[^\]]*\bNewWindowLink\b/.test(code)) {
      sansDirective.add(composant ? relative(APP, composant) : where);
    }
  }
}

// A scan that recognises nothing passes green for ever.
if (liens < 10) {
  console.error(
    `check-new-window-links : ${liens} lien(s) target="_blank" seulement — le scan ne lit plus les gabarits.`,
  );
  process.exit(1);
}
if (sansDirective.size || libellesMuets.length) {
  if (sansDirective.size) {
    console.error(
      'check-new-window-links : composant(s) ouvrant une nouvelle fenêtre sans importer NewWindowLink :',
    );
    for (const c of sansDirective) console.error('  ' + c);
    console.error(
      '  → ajouter `NewWindowLink` (shared/new-window-link) aux `imports` du composant : c’est lui qui dit « (nouvelle fenêtre) »',
    );
  }
  if (libellesMuets.length) {
    console.error(
      'check-new-window-links : lien nommé par un aria-label, qui masque la mention de la nouvelle fenêtre :',
    );
    for (const l of libellesMuets) console.error('  ' + l);
    console.error('  → `[attr.aria-label]="…"` alimenté par `newWindowLabel(libellé)`');
  }
  process.exit(1);
}
console.log(`check-new-window-links : ${liens} lien(s) target="_blank", tous annoncés.`);
