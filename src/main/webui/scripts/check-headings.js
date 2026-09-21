#!/usr/bin/env node
'use strict';

/*
 * One `<h1>` per screen, and `mat-card-title` written as an attribute.
 *
 * `docs/developpement.md § Accessibilité` has said both for a while, and the
 * repository followed them 107 times out of 126 — the RGAA audit (issue #43)
 * found the other 19: `/login` and the three legal pages with no `<h1>` at
 * all, `/notifications` with two, and fourteen `<mat-card-title>` written as
 * an element, which renders a `<div>`: big bold text carrying no semantics.
 * The numbered steps of the two import wizards were among them, so the person
 * reading that screen with a screen reader could not come back to step 2.
 *
 * What is checked, and on what:
 *
 * - every **routed** component of `app.routes.ts` carries exactly one `<h1>`.
 *   Routed, not every `*-page.html`: the four tabs of `/diagnostic` and the
 *   five renderings of `/journee` are components of a page that already
 *   carries its own — `staffing-page` even hides its header behind an
 *   `entete` input for that reason. A shell is excluded the same way: its
 *   children are the screens.
 * - no `<mat-card-title>` anywhere is written as an element.
 *
 *   node scripts/check-headings.js
 */
const { readFileSync, existsSync } = require('node:fs');
const { readdirSync, statSync } = require('node:fs');
const { join, relative, dirname } = require('node:path');

const APP = join(__dirname, '..', 'src', 'app');
const ROUTES = join(APP, 'app.routes.ts');

/**
 * Screens this check accepts without exactly one `<h1>`, each with the reason.
 * The espace animateur is the accessibility groundwork of issue #38 — its four
 * screens carry `<h2>`s under no `<h1>` at all, and deciding what their `<h1>`
 * says belongs to that issue, not to this one. Removing these four entries is
 * part of closing it — and an entry whose screen has since grown its single
 * `<h1>` is reported below, so the list cannot outlive the debt it records.
 */
const EXCEPTIONS_ASSUMEES = {
  'pages/espace-animateur/espace-planning-page.html': 'socle a11y de l’espace animateur — #38',
  'pages/espace-animateur/espace-echanges-page.html': 'socle a11y de l’espace animateur — #38',
  'pages/espace-animateur/espace-disponibilites-page.html':
    'socle a11y de l’espace animateur — #38',
  'pages/espace-animateur/espace-aide-page.html': 'socle a11y de l’espace animateur — #38',
};

/** Every `.html` and `.ts` under `app/`, for the `mat-card-title` sweep. */
function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if ((name.endsWith('.html') || name.endsWith('.ts')) && !name.endsWith('.spec.ts'))
      out.push(path);
  }
  return out;
}

/**
 * The components `app.routes.ts` loads, by their module path. A shell is left
 * out: it wraps the screens rather than being one.
 */
function composantsRoutes() {
  const source = readFileSync(ROUTES, 'utf8');
  const chemins = new Set();
  for (const [, chemin] of source.matchAll(/import\('\.\/((?:pages|shell)\/[\w/-]+)'\)/g)) {
    if (!chemin.endsWith('-shell')) chemins.add(chemin);
  }
  return [...chemins].sort();
}

/**
 * The body of an inline `template:` literal, `${}` interpolations included and
 * their nesting respected. The whole `.ts` would not do: an `<h1` in a comment
 * or in a string constant would be counted as a heading of the screen.
 */
function litteralGabarit(source) {
  const start = /template:\s*`/.exec(source);
  if (!start) return null;
  let depth = 0;
  for (let i = start.index + start[0].length; i < source.length; i++) {
    const char = source[i];
    if (char === '\\') i++;
    else if (char === '$' && source[i + 1] === '{') {
      depth++;
      i++;
    } else if (char === '}' && depth > 0) depth--;
    else if (char === '`' && depth === 0) return source.slice(start.index + start[0].length, i);
  }
  return null;
}

/** The template of a component: its `templateUrl`, or the inline `template`. */
function gabarit(cheminModule) {
  const ts = join(APP, `${cheminModule}.ts`);
  if (!existsSync(ts)) return null;
  const source = readFileSync(ts, 'utf8');
  const url = /templateUrl:\s*'([^']+)'/.exec(source);
  if (url) {
    const html = join(dirname(ts), url[1]);
    return existsSync(html)
      ? { nom: relative(APP, html), source: readFileSync(html, 'utf8') }
      : null;
  }
  const inline = litteralGabarit(source);
  return inline === null ? null : { nom: relative(APP, ts), source: inline };
}

const sansH1 = [];
const tropDeH1 = [];
const elements = [];
const exceptionsMortes = new Set(Object.keys(EXCEPTIONS_ASSUMEES));
let ecrans = 0;

for (const cheminModule of composantsRoutes()) {
  const vue = gabarit(cheminModule);
  if (!vue) continue;
  ecrans++;
  const compte = (vue.source.match(/<h1[\s>]/g) ?? []).length;
  if (vue.nom in EXCEPTIONS_ASSUMEES) {
    // An exception stays dead only while the screen still needs it: one that
    // has grown its single `<h1>` is a line to delete, not a silence to keep.
    if (compte !== 1) exceptionsMortes.delete(vue.nom);
    continue;
  }
  if (compte === 0) sansH1.push(vue.nom);
  else if (compte > 1) tropDeH1.push(`${vue.nom} (${compte})`);
}

for (const file of walk(APP, []).sort()) {
  const source = readFileSync(file, 'utf8');
  for (const match of source.matchAll(/<mat-card-title[\s>]/g)) {
    elements.push(`${relative(APP, file)}:${source.slice(0, match.index).split('\n').length}`);
  }
}

// A scan that recognises nothing passes green for ever.
if (ecrans < 20) {
  console.error(
    `check-headings : ${ecrans} écran(s) seulement reconnu(s) dans app.routes.ts — le scan ne lit plus les routes.`,
  );
  process.exit(1);
}
if (sansH1.length || tropDeH1.length || elements.length || exceptionsMortes.size) {
  if (sansH1.length) {
    console.error('check-headings : écran(s) sans <h1> :');
    for (const e of sansH1) console.error('  ' + e);
    console.error(
      '  → `<h1 mat-card-title>` sur le titre principal de l’écran ; naviguer par titres est le premier réflexe au lecteur d’écran',
    );
  }
  if (tropDeH1.length) {
    console.error('check-headings : écran(s) portant plusieurs <h1> :');
    for (const e of tropDeH1) console.error('  ' + e);
    console.error('  → un seul est le titre de l’écran, les autres passent en <h2>');
  }
  if (elements.length) {
    console.error('check-headings : <mat-card-title> écrit(s) comme élément (rend une <div>) :');
    for (const e of elements) console.error('  ' + e);
    console.error(
      '  → `<h2 mat-card-title>` : l’attribut sur une balise de titre rend exactement la même chose',
    );
  }
  if (exceptionsMortes.size) {
    console.error(
      "check-headings : exception(s) ne correspondant plus à un écran, ou dont l'écran porte désormais son <h1> unique :",
    );
    for (const e of exceptionsMortes) console.error('  ' + e);
  }
  process.exit(1);
}
console.log(
  `check-headings : ${ecrans} écran(s), un <h1> chacun (${Object.keys(EXCEPTIONS_ASSUMEES).length} exception(s) assumée(s)), aucun <mat-card-title> écrit comme élément.`,
);
