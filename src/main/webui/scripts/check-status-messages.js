#!/usr/bin/env node
'use strict';

/*
 * The outcome of an action is announced, never merely printed.
 *
 * `shared/status-message.ts` exists for exactly this: an error is a
 * `role="alert"`, a success or a verdict a `role="status"`, and the region is
 * created with its text — which is what makes assistive technology announce it.
 * The audit of issue #42 still found some thirty `<p>{{ error() }}</p>`
 * written next to it: a load that failed, a save refused, the verdict an
 * analysis screen exists to give — all silent to a screen reader, and, below
 * the fold, silent to everybody.
 *
 * The rule, therefore: an element whose whole content is the interpolation of
 * an error, a message or a verdict carries a `role` or an `aria-live` — or,
 * better, is an `<app-status-message>`. The shape is recognised by the name
 * the template reads, which is what every one of the audited cases shared:
 * `error()`, `erreur()`, `message()`, `erreurConfirmation()`,
 * `rapport()!.message`, `verdict`… A dialog's content is read when it opens,
 * so a `MatDialog` template is not exempt by nature but by exception, argued
 * below.
 *
 *   node scripts/check-status-messages.js
 */
const { readdirSync, readFileSync, statSync } = require('node:fs');
const { join, relative } = require('node:path');

const SOURCES = join(__dirname, '..', 'src');

/**
 * An element whose content is one interpolation, optionally preceded by a
 * decorative icon. Quoted attribute values are skipped whole, as a binding is
 * free to hold a `>`.
 */
const ELEMENT =
  /<(p|div|span|strong|em)\b((?:"[^"]*"|'[^']*'|[^>"'])*)>\s*(?:<mat-icon\b[^>]*>[^<]*<\/mat-icon>\s*)?\{\{\s*([^}]+?)\s*\}\}\s*<\/\1>/g;
/** The last name the interpolation reads, pipes and fallbacks stripped. */
function nomLu(expression) {
  const sansPipe = expression.split(/\s\|\s/)[0];
  const sansRepli = sansPipe.split('??')[0].trim();
  const noms = sansRepli.match(/[A-Za-z_$][\w$]*/g) ?? [];
  return noms.at(-1) ?? '';
}
const ANNONCE = /(^|[a-z])(error|erreur|message|verdict)$/i;
const DEJA_ANNONCE = /\brole\s*=\s*"(alert|status|log)"|\baria-live\s*=/;

/**
 * Elements this check accepts without a live region of their own, as
 * `'<path> {{ <expression> }}': '<why>'` — keyed on what the element reads
 * rather than on its line, so an edit above it does not orphan the entry. A new
 * entry is a decision written down here rather than a silence.
 */
const EXCEPTIONS_ASSUMEES = {
  'app/pages/creneaux/creneau-derivation-dialog.html {{ anomalie.message }}':
    "une ligne de la liste d'anomalies de l'aperçu, lue avec le dialogue qui l'ouvre",
  'app/pages/creneaux/creneau-serie-dialog.html {{ anomalie.message }}':
    "une ligne de la liste d'anomalies de l'aperçu, lue avec le dialogue qui l'ouvre",
  'app/pages/creneaux/journees-types-application-dialog.html {{ anomalie.message }}':
    "une ligne de la liste d'anomalies de l'aperçu, lue avec le dialogue qui l'ouvre",
  'app/pages/creneaux/creneaux-page.html {{ faisabilite.message }}':
    'une part du bilan du contrôle, annoncé en bloc par le role="status" de `.controle-bilan`',
  'app/pages/creneaux/creneaux-page.html {{ anomalie.message }}':
    "une ligne de la liste qui détaille le bilan, lequel est déjà annoncé : l'annoncer aussi la lirait deux fois",
  'app/pages/ouvertures/ouvertures-page.html {{ anomalie.message }}':
    "une ligne de la liste d'anomalies de l'écran, du contenu et non le résultat d'une action",
  'app/pages/notifications/notifications-page.html {{ notification.message }}':
    'une entrée du journal des notifications, du contenu ; chacune a été annoncée par le snack-bar en arrivant',
  'app/shared/confirm-dialog.ts {{ data.message }}':
    "le contenu d'un MatDialog est lu à son ouverture : un role de plus ferait une double annonce",
  'app/shared/prompt-dialog.ts {{ data.message }}':
    "le contenu d'un MatDialog est lu à son ouverture : un role de plus ferait une double annonce",
  'app/shared/notification-snack.ts {{ data.message }}':
    'MatSnackBar annonce lui-même son contenu par le LiveAnnouncer du CDK',
  'app/shared/feasibility-banner.ts {{ report.message }}':
    "bandeau présent dès le rendu de l'écran, qui ne change pas après une action",
  'app/shared/feasibility-banner.ts {{ message }}':
    "bandeau présent dès le rendu de l'écran, qui ne change pas après une action",
  'app/shared/work-in-progress-banner.ts {{ message() }}':
    "bandeau statique d'un écran en chantier, présent dès le rendu",
};

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if ((name.endsWith('.html') || name.endsWith('.ts')) && !name.endsWith('.spec.ts'))
      out.push(path);
  }
  return out;
}

const muets = [];
const exceptionsMortes = new Set(Object.keys(EXCEPTIONS_ASSUMEES));
let annonces = 0;
for (const file of walk(join(SOURCES, 'app'), []).sort()) {
  const name = relative(SOURCES, file);
  const source = readFileSync(file, 'utf8');
  annonces += (source.match(/<app-status-message\b/g) ?? []).length;
  for (const match of source.matchAll(ELEMENT)) {
    const [, , attributs, expression] = match;
    if (!ANNONCE.test(nomLu(expression))) continue;
    if (DEJA_ANNONCE.test(attributs)) continue;
    const cle = `${name} {{ ${expression} }}`;
    if (cle in EXCEPTIONS_ASSUMEES) {
      exceptionsMortes.delete(cle);
      continue;
    }
    muets.push(`${name}:${source.slice(0, match.index).split('\n').length} {{ ${expression} }}`);
  }
}

// A scan that recognises nothing passes green for ever.
if (annonces < 20) {
  console.error(
    `check-status-messages : ${annonces} <app-status-message> seulement trouvé(s) — le scan ne lit plus les gabarits.`,
  );
  process.exit(1);
}
if (muets.length || exceptionsMortes.size) {
  if (muets.length) {
    console.error("check-status-messages : message d'erreur ou de verdict jamais annoncé :");
    for (const m of muets) console.error('  ' + m);
    console.error(
      '  → `<app-status-message [text]="error() ?? \'\'" tone="error" />` (un verdict : tone="info") ; sinon un `role` explicite, ou une entrée dans EXCEPTIONS_ASSUMEES avec sa raison',
    );
  }
  if (exceptionsMortes.size) {
    console.error('check-status-messages : exception(s) ne correspondant plus à un message :');
    for (const e of exceptionsMortes) console.error('  ' + e);
  }
  process.exit(1);
}
console.log(
  `check-status-messages : ${annonces} <app-status-message>, aucun message d'erreur ou de verdict muet.`,
);
