#!/usr/bin/env node
'use strict';

/*
 * A form control is a Material one, never a bare element.
 *
 * `Paramètres › Organisation` and `Légal` shipped as a column of unstyled
 * `<input>` and `<select>`: nothing themes a native control, and a unit test
 * renders without CSS, so nobody saw it. A text field is a `mat-form-field`
 * around an `<input matInput>` (label, hint, error, focus ring, light and dark
 * from the one theme); a native list is `<select matNativeControl>` in the
 * same field. The rule: every `<input>`, `<select>` and `<textarea>` of a
 * template, inline ones included, carries `matInput`, `matNativeControl`,
 * `matAutocomplete` or `matChipInputFor` and sits inside a `mat-form-field` —
 * Material accepts a `matInput` outside one, unthemed, so the nesting is
 * checked here — or is a slider thumb inside its `mat-slider`, or is a hidden
 * file picker (the button that opens it is the visible control), or is argued
 * in EXCEPTIONS_ASSUMEES.
 *
 *   node scripts/check-form-controls.js
 */
const { readFileSync } = require('node:fs');
const { join, relative } = require('node:path');
const { sourceFiles } = require('./source-files');

const SOURCES = join(__dirname, '..', 'src');
const CONTROL = /<(input|select|textarea)\b((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
const MATERIAL = /\b(matInput|matNativeControl|matAutocomplete|matChipInputFor)\b/;
const SLIDER_THUMB = /\b(matSliderThumb|matSliderStartThumb|matSliderEndThumb)\b/;
/** A file picker is hidden and opened by a button: the button is the control. */
const FILE_PICKER_HIDDEN = /\btype="file"[^]*\bhidden\b|\bhidden\b[^]*\btype="file"/;

/**
 * Controls this check accepts bare, as `'<path> <tag>#<name>': '<why>'` where
 * the name is the template reference (`#ref`) or the first class. A new entry
 * is a decision written down here, not a silence.
 */
const EXCEPTIONS_ASSUMEES = {
  'app/pages/editions/editions-page.html input.edition-nom-input':
    "renommage en place dans une cellule de tableau : le champ n'a l'air d'un champ qu'au survol et au focus (bordure et fond), une colonne de champs encadrés se lirait comme un formulaire",
};

/** Comments hold `<input>` in prose: HTML comments, block comments and line comments are blanked. */
function withoutComments(source) {
  return source
    .replace(/<!--[^]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\*[^]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|\s)\/\/.*$/gm, '$1');
}

/** Whether `index` sits inside an open `<tag>` of `source` (open tags before it minus closed ones). */
function inside(source, index, tag) {
  const before = source.slice(0, index);
  const count = (pattern) => (before.match(pattern) ?? []).length;
  return count(new RegExp(`<${tag}\\b`, 'g')) > count(new RegExp(`</${tag}>`, 'g'));
}

function nameOf(attributes) {
  return (attributes.match(/#([\w-]+)/) ?? attributes.match(/class="([^"\s]+)/))?.[1] ?? '';
}

const nus = [];
const exceptionsMortes = new Set(Object.keys(EXCEPTIONS_ASSUMEES));
let controles = 0;
for (const file of sourceFiles(join(SOURCES, 'app'), ['.html', '.ts'])) {
  const name = relative(SOURCES, file);
  const source = withoutComments(readFileSync(file, 'utf8'));
  for (const match of source.matchAll(CONTROL)) {
    controles++;
    const attributes = match[2];
    if (
      (MATERIAL.test(attributes) && inside(source, match.index, 'mat-form-field')) ||
      (SLIDER_THUMB.test(attributes) && inside(source, match.index, 'mat-slider')) ||
      FILE_PICKER_HIDDEN.test(attributes)
    )
      continue;
    const key = `${name} ${match[1]}${nameOf(attributes) ? (/#/.test(attributes) ? '#' : '.') + nameOf(attributes) : ''}`;
    if (key in EXCEPTIONS_ASSUMEES) {
      exceptionsMortes.delete(key);
      continue;
    }
    nus.push(`${name}:${source.slice(0, match.index).split('\n').length} <${match[1]}> (${key})`);
  }
}

// A scan that recognises nothing passes green for ever.
if (controles < 40) {
  console.error(
    `check-form-controls : ${controles} contrôle(s) seulement trouvé(s) — le scan ne lit plus les gabarits.`,
  );
  process.exit(1);
}
if (nus.length || exceptionsMortes.size) {
  if (nus.length) {
    console.error('check-form-controls : champ de saisie nu, hors mat-form-field :');
    for (const n of nus) console.error('  ' + n);
    console.error(
      '  → `<mat-form-field appearance="outline"><mat-label>…</mat-label><input matInput …></mat-form-field>` (liste native : `<select matNativeControl>`) ; sinon une entrée dans EXCEPTIONS_ASSUMEES avec sa raison',
    );
  }
  if (exceptionsMortes.size) {
    console.error('check-form-controls : exception(s) ne correspondant plus à un champ :');
    for (const e of exceptionsMortes) console.error('  ' + e);
  }
  process.exit(1);
}
console.log(`check-form-controls : ${controles} champ(s) de saisie, tous Material ou argumentés.`);
