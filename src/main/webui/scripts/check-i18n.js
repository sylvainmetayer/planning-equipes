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
//   long         a message over 200 characters outside the user guides, the
//                legal pages and the messages that name a legal rule with its
//                article
//                -> a screen that explains instead of answering: the prose
//                   that the pass of issue #417 removed comes back one hint
//                   at a time unless something refuses it.
//   homonyme     one id carries two French texts in two places
//                -> the extractor keeps one and only warns; the English
//                   catalogue holds a single string, so one of the two
//                   places reads as the other in English
//   ordre        the ids of messages.en.json out of code-unit order
//                -> every branch appends its ids at the end of the file, and
//                   two of them conflict there; sorted, each one lands at its
//                   own place
//
// Run it with `npm run i18n-check`.

const { spawnSync } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { byCodeUnit } = require('./code-unit-order');

const WEBUI = join(__dirname, '..');
const CATALOGUE = join(WEBUI, 'public/i18n/messages.en.json');
/** Ids listed in full before the report elides the rest — enough to act on, short enough to read. */
const MAX_LISTES = 20;

const PLACEHOLDER = /\{\$[^}]*\}/g;

function placeholders(message) {
  return (message.match(PLACEHOLDER) ?? []).sort();
}

/**
 * The ids the extractor saw with two different texts, from its « Duplicate
 * messages with id » warnings — whitespace aside, which the templates vary.
 */
function homonymes(avertissements) {
  const textes = new Map();
  let id = null;
  for (const ligne of avertissements.split('\n')) {
    const entete = /Duplicate messages with id "([^"]+)"/.exec(ligne);
    if (entete) {
      id = entete[1];
      textes.set(id, new Set());
      continue;
    }
    const occurrence = /^\s+- "(.*)" : /.exec(ligne);
    if (id !== null && occurrence) {
      textes.get(id).add(occurrence[1].replace(/\s+/g, ' ').trim());
    } else if (ligne.trim() !== '') {
      id = null;
    }
  }
  return new Map([...textes].filter(([, variantes]) => variantes.size > 1));
}

function extraire(webui = WEBUI, conflits = new Map()) {
  const sortie = mkdtempSync(join(tmpdir(), 'planning-i18n-'));
  try {
    // The workspace's own Angular CLI, run by this very Node: nothing is
    // looked up on the PATH.
    const ng = require.resolve('@angular/cli/bin/ng.js', { paths: [webui] });
    const extraction = spawnSync(
      process.execPath,
      [ng, 'extract-i18n', '--format=json', `--output-path=${sortie}`],
      { cwd: webui, stdio: ['ignore', 'ignore', 'pipe'], encoding: 'utf8' },
    );
    process.stderr.write(extraction.stderr ?? '');
    if (extraction.status !== 0) {
      throw new Error(`ng extract-i18n a échoué (code ${extraction.status}).`);
    }
    for (const [id, variantes] of homonymes(extraction.stderr ?? '')) {
      conflits.set(id, variantes);
    }
    return JSON.parse(readFileSync(join(sortie, 'messages.json'), 'utf8')).translations;
  } finally {
    rmSync(sortie, { recursive: true, force: true });
  }
}

/**
 * The ceiling on a screen message, in characters once whitespace is collapsed.
 * Beyond it a message is documentation, and documentation has two homes — the
 * help page and the espace's help — where length is not the question. The
 * legal pages are text by nature, and a warning that names a rule with its
 * article is kept whole: shortening it is what would cost the tool its
 * defensibility. The liability wording of the legal-disable dialog
 * (`constraints.legalDisable.*`) is legal text too, only spoken on a screen.
 */
const PLAFOND = 200;
const HORS_PLAFOND =
  /^(aide|espace\.aide|mentions|cgu|confidentialite|accessibilite|legalText|constraints\.legalDisable)\./;
const CITE_UN_ARTICLE = /\b(art(?:icle|\.)\s*[LRD]\.?\s?\d|[LRD]\d{4}-\d)/i;

function messagesTropLongs(source) {
  return Object.keys(source)
    .filter((id) => !HORS_PLAFOND.test(id))
    .filter((id) => !CITE_UN_ARTICLE.test(source[id]))
    .filter((id) => source[id].replace(/\s+/g, ' ').trim().length > PLAFOND);
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

/**
 * Quoted spans: « … » on the French side, “ … ” or " … " on the English one.
 * The blanks inside the quotes are trimmed in code rather than by `\s*` on
 * both sides of a lazy group, which backtracks.
 */
const CITATION_FR = /«([^«»]+)»/g;
const CITATION_EN = /[«“"]([^«»“”"]+)[»”"]/g;

function citations(message, motif) {
  return [...message.matchAll(motif)].map((found) => found[1].trim());
}

/** `texte` without its trailing blanks and `.:?!` — `/[ .:?!]+$/` without the retries. */
function withoutTrailingPunctuation(texte) {
  let fin = texte.length;
  while (fin > 0 && ' .:?!'.includes(texte[fin - 1])) {
    fin -= 1;
  }
  return texte.slice(0, fin);
}

/** Same string modulo spacing, apostrophe shape, case and trailing punctuation. */
function comparable(texte) {
  return withoutTrailingPunctuation(
    texte
      .replace(/[\u2019\u2018]/g, "'")
      .replace(/[\u00a0\u202f]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase(),
  );
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
    const citeesEn = new Set(citations(anglais[id], CITATION_EN).map(comparable));
    for (const citee of citations(message, CITATION_FR)) {
      if (citee.trim().split(/\s+/).length < 2) {
        continue;
      }
      const candidats = (libelles.get(comparable(citee)) ?? []).filter((autre) => autre !== id);
      const attendus = candidats.filter((autre) => autre in anglais).map((autre) => anglais[autre]);
      if (attendus.length === 0 || attendus.some((attendu) => citeesEn.has(comparable(attendu)))) {
        continue;
      }
      rapport.set(id, [...(rapport.get(id) ?? []), { citee, attendus }]);
    }
  }
  return rapport;
}

const homonymesSource = new Map();
const source = extraire(WEBUI, homonymesSource);
const anglais = JSON.parse(readFileSync(CATALOGUE, 'utf8'));

const manquants = Object.keys(source).filter((id) => !(id in anglais));
const orphelins = Object.keys(anglais).filter((id) => !(id in source));
const divergents = Object.keys(source)
  .filter((id) => id in anglais)
  .filter((id) => placeholders(source[id]).join('|') !== placeholders(anglais[id]).join('|'));

const libelles = libellesDivergents(source, anglais);
const longs = messagesTropLongs(source);
const ids = Object.keys(anglais);
const desordre = ids.filter((id, i) => i > 0 && byCodeUnit(ids[i - 1], id) >= 0);

if (
  manquants.length === 0 &&
  orphelins.length === 0 &&
  divergents.length === 0 &&
  libelles.size === 0 &&
  longs.length === 0 &&
  homonymesSource.size === 0 &&
  desordre.length === 0
) {
  console.log(
    `i18n-check : ${Object.keys(source).length}/${Object.keys(source).length} messages traduits, placeholders cohérents, libellés cités alignés sur l'écran, aucun message au-dessus de ${PLAFOND} caractères hors aide et légal.`,
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

if (longs.length > 0) {
  lister(
    `Messages de plus de ${PLAFOND} caractères hors aide, pages légales et règles citées avec leur article — à resserrer, ou à déplacer vers l'aide`,
    longs,
    (id) =>
      `      ${source[id].replace(/\s+/g, ' ').trim().length} caractères : ${JSON.stringify(source[id].replace(/\s+/g, ' ').trim().slice(0, 90))}…`,
  );
}

if (homonymesSource.size > 0) {
  lister(
    'Un même id pour deux textes — en anglais, un des deux endroits affiche l’autre : donner son propre id à chacun',
    [...homonymesSource.keys()],
    (id) =>
      [...homonymesSource.get(id)].map((texte) => `      ${JSON.stringify(texte)}`).join('\n'),
  );
}

if (desordre.length > 0) {
  lister(
    "Hors de l'ordre des ids — `jq -S . public/i18n/messages.en.json` remet le fichier en ordre",
    desordre,
  );
}

console.error('\nCorriger public/i18n/messages.en.json, puis relancer `npm run i18n-check`.');
process.exit(1);
