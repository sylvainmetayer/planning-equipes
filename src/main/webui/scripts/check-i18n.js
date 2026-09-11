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
//   libellé      a message quotes « another message » verbatim, and its own
//                translation quotes something the English UI never displays
//                -> the English guide sends a reader looking for a button that
//                   reads differently on screen. Naming a control is only
//                   worth it if the reader finds that exact wording.
//   périmé       the French source was rewritten under the same id and the
//                English string was not touched (`--modifies` only)
//                -> the English screen still says the previous version, and
//                   none of the checks above can tell: ids and placeholders
//                   are intact. A text pass is, by construction, many messages
//                   rewritten under their id — this is the check that keeps
//                   the two languages moving together.
//
// Run it with `npm run i18n-check`. With `--modifies [base]` it also extracts
// the sources of `base` (default `origin/main`) in a throwaway worktree and
// reports the ids whose French changed while the English did not — a ratchet
// on what the branch rewrites, not a barrier on the whole catalogue.

const { execFileSync } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync, symlinkSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, relative } = require('node:path');

const WEBUI = join(__dirname, '..');
const CATALOGUE = join(WEBUI, 'public/i18n/messages.en.json');
/** Ids listed in full before the report elides the rest — enough to act on, short enough to read. */
const MAX_LISTES = 20;

const PLACEHOLDER = /\{\$[^}]*\}/g;

function placeholders(message) {
  return (message.match(PLACEHOLDER) ?? []).sort();
}

function extraire(webui = WEBUI) {
  const sortie = mkdtempSync(join(tmpdir(), 'planning-i18n-'));
  try {
    execFileSync('npx', ['ng', 'extract-i18n', '--format=json', `--output-path=${sortie}`], {
      cwd: webui,
      stdio: ['ignore', 'ignore', 'inherit'],
    });
    return JSON.parse(readFileSync(join(sortie, 'messages.json'), 'utf8')).translations;
  } finally {
    rmSync(sortie, { recursive: true, force: true });
  }
}

/** The base named after `--modifies`, or null when the ratchet is not asked for. */
function baseDemandee() {
  const drapeau = process.argv.indexOf('--modifies');
  return drapeau < 0 ? null : (process.argv[drapeau + 1] ?? 'origin/main');
}

/**
 * The sources and the English catalogue as `base` had them.
 *
 * The sources are extracted, not diffed: an `i18n` block spans several lines,
 * and the id it carries is rarely on the line that changed, so a diff cannot
 * say which message moved. A detached worktree of `base` gets this tree's
 * `node_modules` by symlink and runs the same extraction — some forty seconds,
 * the price of an answer that does not guess.
 */
function etatDeLaBase(base) {
  const depot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
    cwd: WEBUI,
    encoding: 'utf8',
  }).trim();
  const arbre = mkdtempSync(join(tmpdir(), 'planning-i18n-base-'));
  execFileSync('git', ['worktree', 'add', '--detach', arbre, base], {
    cwd: depot,
    stdio: 'ignore',
  });
  try {
    const webui = join(arbre, relative(depot, WEBUI));
    symlinkSync(join(WEBUI, 'node_modules'), join(webui, 'node_modules'), 'dir');
    execFileSync('node', ['scripts/generate-version.js'], { cwd: webui, stdio: 'ignore' });
    return {
      source: extraire(webui),
      anglais: JSON.parse(readFileSync(join(webui, 'public/i18n/messages.en.json'), 'utf8')),
    };
  } finally {
    execFileSync('git', ['worktree', 'remove', '--force', arbre], { cwd: depot, stdio: 'ignore' });
  }
}

/**
 * Ids the branch rewrote in French without touching the English. Spacing,
 * apostrophe shape, case and trailing punctuation do not count as a rewrite
 * (see `comparable`); a placeholder rename is already reported elsewhere.
 */
function traductionsPerimees(source, anglais, base) {
  return Object.keys(source)
    .filter((id) => id in base.source && id in anglais && id in base.anglais)
    .filter((id) => comparable(source[id]) !== comparable(base.source[id]))
    .filter((id) => anglais[id] === base.anglais[id]);
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

/** Quoted spans: « … » on the French side, “ … ” or " … " on the English one. */
const CITATION_FR = /«\s*([^«»]+?)\s*»/g;
const CITATION_EN = /[«“"]\s*([^«»“”"]+?)\s*[»”"]/g;

function citations(message, motif) {
  return [...message.matchAll(motif)].map((found) => found[1]);
}

/** Same string modulo spacing, apostrophe shape, case and trailing punctuation. */
function comparable(texte) {
  return texte
    .replace(/[\u2019\u2018]/g, "'")
    .replace(/[\u00a0\u202f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
    .replace(/[ .:?!]+$/, '');
}

/**
 * Ids whose whole source string is exactly this label. Single words are left
 * out: « tous » or « continu » quoted in a sentence is prose, and would collide
 * with an unrelated filter label.
 */
function indexerLibelles(source) {
  const index = new Map();
  for (const [id, message] of Object.entries(source)) {
    if (message.trim().split(/\s+/).length < 2) {
      continue;
    }
    const cle = comparable(message);
    index.set(cle, [...(index.get(cle) ?? []), id]);
  }
  return index;
}

/**
 * Ids whose French quotes a control by its exact label, while their English
 * quotes none of that control's English labels. Reported per id, with what the
 * screen actually reads.
 */
function libellesDivergents(source, anglais) {
  const libelles = indexerLibelles(source);
  const rapport = new Map();
  for (const [id, message] of Object.entries(source)) {
    if (!(id in anglais)) {
      continue;
    }
    const citeesEn = citations(anglais[id], CITATION_EN).map(comparable);
    for (const citee of citations(message, CITATION_FR)) {
      if (citee.trim().split(/\s+/).length < 2) {
        continue;
      }
      const candidats = (libelles.get(comparable(citee)) ?? []).filter((autre) => autre !== id);
      const attendus = candidats.filter((autre) => autre in anglais).map((autre) => anglais[autre]);
      if (
        attendus.length === 0 ||
        attendus.some((attendu) => citeesEn.includes(comparable(attendu)))
      ) {
        continue;
      }
      rapport.set(id, [...(rapport.get(id) ?? []), { citee, attendus }]);
    }
  }
  return rapport;
}

const source = extraire();
const anglais = JSON.parse(readFileSync(CATALOGUE, 'utf8'));
const base = baseDemandee();
const perimes = base === null ? [] : traductionsPerimees(source, anglais, etatDeLaBase(base));

const manquants = Object.keys(source).filter((id) => !(id in anglais));
const orphelins = Object.keys(anglais).filter((id) => !(id in source));
const divergents = Object.keys(source)
  .filter((id) => id in anglais)
  .filter((id) => placeholders(source[id]).join('|') !== placeholders(anglais[id]).join('|'));

const libelles = libellesDivergents(source, anglais);

if (
  manquants.length === 0 &&
  orphelins.length === 0 &&
  divergents.length === 0 &&
  libelles.size === 0 &&
  perimes.length === 0
) {
  const cliquet = base === null ? '' : `, aucune traduction périmée par rapport à ${base}`;
  console.log(
    `i18n-check : ${Object.keys(source).length}/${Object.keys(source).length} messages traduits, placeholders cohérents, libellés cités alignés sur l'écran${cliquet}.`,
  );
  process.exit(0);
}

console.error('i18n-check : le catalogue anglais a dérivé des chaînes sources.');

if (manquants.length > 0) {
  lister(
    "Sans traduction anglaise — ces écrans s'afficheront en français",
    manquants,
    (id) => `      source : ${JSON.stringify(source[id])}`,
  );
}
if (orphelins.length > 0) {
  lister('Traduits mais absents du code — à supprimer de messages.en.json', orphelins);
}
if (divergents.length > 0) {
  lister(
    "Placeholders divergents — casse à l'affichage, en anglais uniquement",
    divergents,
    (id) =>
      `      source : ${placeholders(source[id]).join(' ') || '(aucun)'}\n      anglais : ${placeholders(anglais[id]).join(' ') || '(aucun)'}`,
  );
}

if (libelles.size > 0) {
  lister(
    "Libellés cités qui ne correspondent à rien à l'écran en anglais",
    [...libelles.keys()],
    (id) =>
      libelles
        .get(id)
        .map(
          (ecart) =>
            `      cité (fr) : ${JSON.stringify(ecart.citee)}\n` +
            `      à l'écran (en) : ${ecart.attendus.map((attendu) => JSON.stringify(attendu)).join(' | ')}`,
        )
        .join('\n'),
  );
}

if (perimes.length > 0) {
  lister(
    `Source française réécrite depuis ${base}, traduction anglaise inchangée — à retraduire`,
    perimes,
    (id) =>
      `      source : ${JSON.stringify(source[id])}\n      anglais : ${JSON.stringify(anglais[id])}`,
  );
}

console.error('\nCorriger public/i18n/messages.en.json, puis relancer `npm run i18n-check`.');
process.exit(1);
