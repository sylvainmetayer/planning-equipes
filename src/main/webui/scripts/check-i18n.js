#!/usr/bin/env node
'use strict';

// Fails when the English catalogue has drifted from the source strings.
//
// Nothing else catches this drift. `$localize` falls back to the French source
// when an id is missing, so a half-translated screen raises no error at build
// time, no error at runtime, and no error in the tests — it is simply half
// translated, in a language the person who wrote it does not read. That is how
// 56 ids accumulated before this check existed.
//
// Three failure modes, all of them silent without this script:
//
//   missing      an id exists in the code, not in messages.en.json
//                -> that string shows up in French for English users
//   orphan       an id exists in messages.en.json, not in the code
//                -> dead weight, and usually the leftover half of a rename
//   placeholder  same id, different {$…} names between source and translation
//                -> breaks at *display* time, on that one screen, in English
//                   only: the nastiest of the three, and invisible to a build
//
// Run it with `npm run i18n-check`.

const { execFileSync } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');

const CATALOGUE = join(__dirname, '../public/i18n/messages.en.json');
/** Ids listed in full before the report elides the rest — enough to act on, short enough to read. */
const MAX_LISTES = 20;

const PLACEHOLDER = /\{\$[^}]*\}/g;

function placeholders(message) {
  return (message.match(PLACEHOLDER) ?? []).sort();
}

function extraire() {
  const sortie = mkdtempSync(join(tmpdir(), 'planning-i18n-'));
  try {
    execFileSync(
      'npx',
      ['ng', 'extract-i18n', '--format=json', `--output-path=${sortie}`],
      { cwd: join(__dirname, '..'), stdio: ['ignore', 'ignore', 'inherit'] }
    );
    return JSON.parse(readFileSync(join(sortie, 'messages.json'), 'utf8')).translations;
  } finally {
    rmSync(sortie, { recursive: true, force: true });
  }
}

function lister(titre, ids, detail) {
  console.error(`\n${titre} (${ids.length}) :`);
  for (const id of ids.slice(0, MAX_LISTES)) {
    console.error(detail ? `  ${id}\n${detail(id)}` : `  ${id}`);
  }
  if (ids.length > MAX_LISTES) {
    console.error(`  … et ${ids.length - MAX_LISTES} autre(s)`);
  }
}

const source = extraire();
const anglais = JSON.parse(readFileSync(CATALOGUE, 'utf8'));

const manquants = Object.keys(source).filter((id) => !(id in anglais));
const orphelins = Object.keys(anglais).filter((id) => !(id in source));
const divergents = Object.keys(source)
  .filter((id) => id in anglais)
  .filter((id) => placeholders(source[id]).join('|') !== placeholders(anglais[id]).join('|'));

if (manquants.length === 0 && orphelins.length === 0 && divergents.length === 0) {
  console.log(`i18n-check : ${Object.keys(source).length}/${Object.keys(source).length} messages traduits, placeholders cohérents.`);
  process.exit(0);
}

console.error('i18n-check : le catalogue anglais a dérivé des chaînes sources.');

if (manquants.length > 0) {
  lister(
    'Sans traduction anglaise — ces écrans s\'afficheront en français',
    manquants,
    (id) => `      source : ${JSON.stringify(source[id])}`
  );
}
if (orphelins.length > 0) {
  lister(
    'Traduits mais absents du code — à supprimer de messages.en.json',
    orphelins
  );
}
if (divergents.length > 0) {
  lister(
    'Placeholders divergents — casse à l\'affichage, en anglais uniquement',
    divergents,
    (id) => `      source : ${placeholders(source[id]).join(' ') || '(aucun)'}\n      anglais : ${placeholders(anglais[id]).join(' ') || '(aucun)'}`
  );
}

console.error('\nCorriger public/i18n/messages.en.json, puis relancer `npm run i18n-check`.');
process.exit(1);
