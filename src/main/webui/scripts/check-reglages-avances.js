#!/usr/bin/env node
'use strict';

/*
 * « Réglages avancés » is drawn only where something sits behind it.
 *
 * A button that unfolds nothing reads as a promise broken (the Calculer tab
 * once carried one with no machine to name and no curve to draw). So every
 * `<app-reglages-avances>` binds `[utile]` to the condition under which its
 * screen has advanced content on the tab on screen, and a literal `true` is
 * allowed only where the content never goes away — the files listed here, each
 * with what the fold holds. A new screen is a new line only if its content is
 * permanent; otherwise its condition is written in the template.
 *
 *   node scripts/check-reglages-avances.js
 */
const { readFileSync } = require('node:fs');
const { join, relative } = require('node:path');
const { sourceFiles } = require('./source-files');

const APP = join(__dirname, '..', 'src', 'app');
const USAGE = /<app-reglages-avances\b((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;

const TOUJOURS_UTILE = {
  'pages/journee/journee-page.html':
    'the emplacement and game category filters, drawn inside the `shownFilters` guard around the button',
  'pages/parametres/parametres-page.html':
    'the expert tabs (Légal, Organisation, Calcul, Historique…), present on every account',
  'pages/staffing/staffing-page.html': 'the « Confirmer par un calcul » option and its duration',
};

const problemes = [];
let usages = 0;
for (const file of sourceFiles(APP, ['.html', '.ts'])) {
  if (file.endsWith('shared/reglages-avances.ts')) continue;
  const chemin = relative(APP, file);
  for (const match of readFileSync(file, 'utf8').matchAll(USAGE)) {
    usages++;
    const utile = /\[utile\]\s*=\s*"([^"]*)"/.exec(match[1]);
    if (!utile) problemes.push(`${chemin} : [utile] absent`);
    else if (utile[1].trim() === 'true' && !(chemin in TOUJOURS_UTILE)) {
      problemes.push(`${chemin} : [utile]="true" non justifié (voir TOUJOURS_UTILE)`);
    }
  }
}

// A scan that recognises nothing passes green for ever.
if (usages < 5) {
  console.error(
    `check-reglages-avances : ${usages} usage(s) seulement — le scan ne lit plus les gabarits.`,
  );
  process.exit(1);
}
if (problemes.length) {
  console.error('check-reglages-avances : bouton sans contenu avancé garanti :');
  for (const p of problemes) console.error('  ' + p);
  console.error(
    '  → lier `[utile]` à la condition qui dit qu’il y a quelque chose derrière le bouton',
  );
  process.exit(1);
}
console.log(`check-reglages-avances : ${usages} usage(s), tous conditionnés.`);
