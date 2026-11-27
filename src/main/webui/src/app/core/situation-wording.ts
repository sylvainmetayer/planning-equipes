// How a situation of the editions' commissioning reads (`GET /api/editions/situations`):
// one sentence naming the edition and what is wrong, with dates in the reader's
// locale. Shared by the shell's banner and the Éditions page. `$localize` runs at
// call time, never at module scope.

import { parseDateKey } from './date-utils';
import { EditionSituation } from './models';

/** `2026-07-10` → `10/07/2026` (or `07/10/2026` in English), the reader's way. */
export function dateLabel(date: string | null, locale: string): string {
  return date
    ? parseDateKey(date).toLocaleDateString(locale, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      })
    : '';
}

/**
 * The sentence of one situation. `simulatedDate` (`AAAA-MM-JJ`) is the frozen
 * date of the application's clock, or `''` on the real one: it is appended,
 * since a simulated clock is the usual reason for a surprising banner.
 */
export function situationText(
  situation: EditionSituation,
  locale: string,
  simulatedDate: string,
): string {
  const nom = situation.edition.nom;
  const debut = dateLabel(situation.premierJour, locale);
  const fin = dateLabel(situation.dernierJour, locale);
  let text: string;
  switch (situation.type) {
    case 'ACTIVE_TERMINEE':
      text = $localize`:@@editions.situation.activeTerminee:${nom}:nom: est terminée depuis le ${fin}:fin: et reste active.`;
      break;
    case 'INACTIVE_IMMINENTE':
      text = $localize`:@@editions.situation.inactiveImminente:${nom}:nom: commence le ${debut}:debut: et n'est pas active.`;
      break;
    case 'INACTIVE_EN_COURS':
      text = $localize`:@@editions.situation.inactiveEnCours:${nom}:nom: est en cours mais inactive : son espace animateur est fermé.`;
      break;
    case 'AUCUNE_ACTIVE':
      text = $localize`:@@editions.situation.aucuneActive:Aucune édition n'est active alors que ${nom}:nom: est prévue à partir du ${debut}:debut:.`;
      break;
  }
  if (simulatedDate) {
    const date = dateLabel(simulatedDate, locale);
    text += ' ' + $localize`:@@editions.situation.simulee:(date simulée : ${date}:date:)`;
  }
  return text;
}
