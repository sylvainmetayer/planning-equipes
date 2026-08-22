#!/usr/bin/env node
'use strict';

/*
 * Fails the build when the generated index.html carries an inline event
 * handler.
 *
 * The Content-Security-Policy this application serves has no 'unsafe-inline'
 * and no 'unsafe-hashes' on script-src, so the browser refuses to run any
 * `on*=""` attribute. That is the intended posture — but it makes one Angular
 * optimisation silently destructive: critical-CSS inlining ships the real
 * stylesheet as `media="print" onload="this.media='all'"`, and when CSP blocks
 * that handler the sheet stays print-only. Nothing errors. The page simply
 * renders without whatever the critical extraction left behind — which is how
 * the Material Icons @font-face went missing in production while every test
 * was green.
 *
 * The build cannot see it (the HTML is valid), the tests cannot see it (they
 * never load index.html), and CSP violations only appear in a real browser's
 * console. So it is checked here, on the artefact itself.
 */
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const OUTPUT = 'dist/planning-equipes-ui/browser';
const INDEX = join(OUTPUT, 'index.html');

/** `onload=`, `onclick=`, … — an attribute whose value the CSP would have to allow as a script. */
const INLINE_HANDLER = /\son[a-z]+\s*=\s*["']/gi;

let html;
try {
  html = readFileSync(INDEX, 'utf8');
} catch {
  console.error(`check-csp-index : ${INDEX} est introuvable — le build a-t-il tourné ?`);
  process.exit(1);
}

const found = [...html.matchAll(INLINE_HANDLER)].map((match) => {
  const start = Math.max(0, match.index - 90);
  return html.slice(start, match.index + 60).replace(/\s+/g, ' ').trim();
});

if (found.length > 0) {
  console.error(
    `check-csp-index : ${found.length} gestionnaire(s) d'évènement inline dans ${INDEX}.\n` +
      "La CSP servie par l'application les bloque, sans erreur visible : la ressource\n" +
      'concernée est simplement ignorée. Voir planning.securite.csp dans application.properties.\n'
  );
  found.forEach((extrait) => console.error(`  … ${extrait} …`));
  process.exit(1);
}

console.log('check-csp-index : aucun gestionnaire inline, la CSP ne bloquera rien.');
