#!/usr/bin/env node
'use strict';

/*
 * A `<mat-icon>` that carries information says so with a static
 * `aria-hidden="false"`.
 *
 * `MatIcon` sets `aria-hidden="true"` on its host in its constructor unless a
 * **static** `aria-hidden` is present in the template — it reads it through
 * `inject(new HostAttributeToken('aria-hidden'))`, which never sees an Angular
 * binding. A hidden element leaves the accessibility tree with everything it
 * carries: its own `aria-label`, and the `aria-describedby` `MatTooltip` adds
 * to it. So an icon written with `[attr.aria-label]` or a `matTooltip` and
 * nothing else is an icon whose label is never read — the shape audited in
 * issue #39, found on 33 icons, of which several were the only mark of an
 * understaffed stand, a locked day or an animateur without a rest day.
 *
 * The rule, therefore: an icon carrying `aria-label`, `aria-labelledby` or
 * `matTooltip` — bound or literal — also carries `aria-hidden="false"`
 * written out in the template. An icon that genuinely repeats its neighbour's
 * text stays decorative and says *that* out loud, with `aria-hidden="true"`.
 *
 *   node scripts/check-icon-labels.js
 */
const { readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');

const SOURCES = join(__dirname, '..', 'src');
/**
 * Opening tags, including the multi-line ones: a template is not a nesting
 * problem here. Quoted values are skipped whole rather than scanned for `>`,
 * because a binding is free to hold one — `[matTooltip]="n > 1 ? a : b"` cut a
 * naive `[^>]*` short, and every attribute after it became invisible: the check
 * then failed a compliant icon, or passed one it could no longer read.
 */
const ICON = /<mat-icon\b(?:"[^"]*"|'[^']*'|[^>"'])*>/g;
/** What makes an icon informative rather than decorative. */
const LABELLED =
  /\baria-label\s*=|\[attr\.aria-label\]|\baria-labelledby\s*=|\[attr\.aria-labelledby\]|\bmatTooltip\s*=|\[matTooltip\]/;
/**
 * What gives the icon a *name*. A tooltip is not one of these: `MatTooltip`
 * adds an `aria-describedby`, and `MatIcon` forces `role="img"`, a role that
 * takes no name from its content — so a tooltip-only icon is announced as an
 * unnamed graphic, ligature and all.
 */
const NAMED =
  /\baria-label\s*=|\[attr\.aria-label\]|\baria-labelledby\s*=|\[attr\.aria-labelledby\]/;
/** Only a literal counts: `[attr.aria-hidden]` is exactly what MatIcon cannot see. */
const STATIC_ARIA_HIDDEN = /\baria-hidden\s*=\s*"(true|false)"/;

/**
 * Icons this check accepts without `aria-hidden="false"`, each with the reason.
 * An entry is `'<path>:<line>': '<why it is decorative>'`. Empty on purpose:
 * every informative icon of the repository now carries the attribute, and a
 * new exception is a decision to write down here rather than a silence.
 */
const EXCEPTIONS_ASSUMEES = {};

/** Every template of the application: the `.html` files and the inline templates of the `.ts`. */
function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if ((name.endsWith('.html') || name.endsWith('.ts')) && !name.endsWith('.spec.ts'))
      out.push(path);
  }
  return out;
}

const offenders = [];
const sansNom = [];
const exceptionsMortes = new Set(Object.keys(EXCEPTIONS_ASSUMEES));
let informatives = 0;
for (const file of walk(SOURCES, []).sort()) {
  const name = relative(SOURCES, file);
  const source = readFileSync(file, 'utf8');
  for (const match of source.matchAll(ICON)) {
    const tag = match[0];
    if (!LABELLED.test(tag)) continue;
    informatives++;
    const where = `${name}:${source.slice(0, match.index).split('\n').length}`;
    if (where in EXCEPTIONS_ASSUMEES) {
      exceptionsMortes.delete(where);
      continue;
    }
    const declared = STATIC_ARIA_HIDDEN.exec(tag);
    if (declared?.[1] !== 'false') {
      offenders.push(`${where} ${declared ? 'aria-hidden="true"' : 'aucun aria-hidden statique'}`);
      continue;
    }
    if (!NAMED.test(tag)) sansNom.push(where);
  }
}

// A scan that recognises nothing passes green for ever. The repository holds
// dozens of informative icons; none left means the regex stopped reading the
// templates, not that the code improved.
if (!informatives) {
  console.error(
    "check-icon-labels : aucune <mat-icon> porteuse d'information trouvée — le scan ne reconnaît plus les gabarits.",
  );
  process.exit(1);
}
if (offenders.length || sansNom.length || exceptionsMortes.size) {
  if (offenders.length) {
    console.error(
      "check-icon-labels : <mat-icon> porteuse d'information dont le libellé n'est jamais restitué :",
    );
    for (const o of offenders) console.error('  ' + o);
    console.error(
      '  → ajouter `aria-hidden="false"` en dur dans le gabarit (MatIcon ne voit pas une liaison) ; une icône qui ne fait que répéter le texte voisin perd son libellé et son infobulle, ou s\'inscrit dans EXCEPTIONS_ASSUMEES avec sa raison',
    );
  }
  if (sansNom.length) {
    console.error(
      'check-icon-labels : <mat-icon> exposée sans nom accessible — son infobulle en est la description, pas le nom :',
    );
    for (const o of sansNom) console.error('  ' + o);
    console.error(
      '  → ajouter `aria-label` (ou `aria-labelledby`) : MatIcon impose `role="img"`, un rôle qui ne prend pas son nom de son contenu, donc une icône à seule infobulle est annoncée comme un graphique anonyme',
    );
  }
  if (exceptionsMortes.size) {
    console.error('check-icon-labels : exception(s) ne correspondant plus à une icône :');
    for (const e of exceptionsMortes) console.error('  ' + e);
  }
  process.exit(1);
}
console.log(
  `check-icon-labels : ${informatives} <mat-icon> porteuse(s) d'information, toutes restituées et toutes nommées.`,
);
