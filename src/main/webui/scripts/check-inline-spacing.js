#!/usr/bin/env node
'use strict';

/*
 * A line break between two inline elements is not a space.
 *
 * Angular drops a text node made only of whitespace (`preserveWhitespaces:
 * false`), so
 *
 *   <ng-container i18n="@@x">… sont dans</ng-container>
 *   <a routerLink="/regles">Règles du planning</a>
 *
 * renders « … sont dansRègles du planning ». The space survives only inside
 * a text node that holds something else, hence the explicit `&ngsp;` that
 * ends the first line. This script refuses the two shapes that produced it:
 *
 * - a line ending with `</ng-container>` carrying an i18n message, followed by
 *   a line opening a link, a `strong`, `code`, `b`, `em`, `kbd` or another
 *   i18n `ng-container`;
 * - a line ending with the close of one of those inline elements, followed by
 *   a line opening an i18n `ng-container`.
 *
 * A message beginning with a space or a closing punctuation, or ending with a space, is left alone.
 * A `span` is not looked at: it is as often a flex or grid item, where the gap
 * is the layout's and a space would be noise. A `ng-container` renders no box,
 * so one between two lines of prose is always a missing space.
 *
 *   node scripts/check-inline-spacing.js
 */
const { readFileSync } = require('node:fs');
const { join, relative } = require('node:path');
const { sourceFiles } = require('./source-files');

const APP = join(__dirname, '..', 'src', 'app');
// An i18n `ng-container` (its opening tag may sit lines above its end), the
// whitespace that follows it, then the next inline element. Nested
// `ng-container`s are excluded from the body so a match never spans two.
const INLINE = String.raw`<(?:a|strong|code|b|em|kbd)\b`;
const CONTENEUR_I18N = String.raw`<ng-container\b[^>]*\bi18n[^>]*>`;
const CONTENEUR_PUIS_EN_LIGNE = new RegExp(
  String.raw`(${CONTENEUR_I18N}((?:(?!<ng-container)[\s\S])*?)</ng-container>)[ \t]*\r?\n[ \t]*(?=${INLINE}|${CONTENEUR_I18N})`,
  'g',
);
const EN_LIGNE_PUIS_CONTENEUR = new RegExp(
  String.raw`</(?:a|strong|code|b|em|kbd)>[ \t]*\r?\n[ \t]*(${CONTENEUR_I18N}([\s\S]?))`,
  'g',
);
// A message that itself starts or ends with a space or punctuation already carries it.
const ESPACE_FIN = /[\s]$/;
const PONCTUATION_DEBUT = /^[\s;,.:)!?»]/;

const trouves = [];
const signale = (file, contenu, index, extrait) =>
  trouves.push(
    `${relative(APP, file)}:${contenu.slice(0, index).split('\n').length}: ${extrait.trim().slice(-60)}`,
  );
for (const file of sourceFiles(APP, ['.html', '.ts'])) {
  const contenu = readFileSync(file, 'utf8');
  for (const m of contenu.matchAll(CONTENEUR_PUIS_EN_LIGNE)) {
    if (!ESPACE_FIN.test(m[2])) signale(file, contenu, m.index, m[1]);
  }
  for (const m of contenu.matchAll(EN_LIGNE_PUIS_CONTENEUR)) {
    if (!PONCTUATION_DEBUT.test(m[2])) {
      signale(file, contenu, m.index, m[0]);
    }
  }
}

if (trouves.length > 0) {
  console.error(`${trouves.length} saut(s) de ligne entre deux éléments en ligne, sans espace :`);
  trouves.forEach((t) => console.error(`  ${t}`));
  console.error(
    'Terminez la première ligne par `&ngsp;` : Angular retire un nœud fait de blancs seuls.',
  );
  process.exit(1);
}
console.log('check-inline-spacing : aucun espace manquant entre éléments en ligne.');
