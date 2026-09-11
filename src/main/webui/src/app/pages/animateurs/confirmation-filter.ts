// The acknowledgement filters of the Animateurs page (issue #504), as a pure
// function of one row's answer: « jamais confirmés » and « silencieux depuis
// N jours ». Kept apart from the page so the rule — and the day arithmetic —
// is pinned down without rendering a table.

import type { ConfirmationView } from '../../core/models';

/** What the « Accusés » select of the page offers. */
export type ModeAccuses = 'tous' | 'jamais' | 'silence';

/** The number of days the « silencieux depuis » control opens on. */
export const SILENCE_JOURS_DEFAUT = 3;

const JOUR_MS = 24 * 60 * 60 * 1000;

/**
 * Reads the two URL params. `silence` wins when it carries a positive whole
 * number of days; `confirmation=jamais` otherwise; anything else is the
 * default, never an error — an old link degrades to the whole table.
 */
export function readModeAccuses(
  confirmation: string | null,
  silence: string | null,
): { mode: ModeAccuses; jours: number } {
  const jours = silence === null ? NaN : Number(silence);
  if (Number.isInteger(jours) && jours > 0) {
    return { mode: 'silence', jours };
  }
  if (confirmation === 'jamais') {
    return { mode: 'jamais', jours: SILENCE_JOURS_DEFAUT };
  }
  return { mode: 'tous', jours: SILENCE_JOURS_DEFAUT };
}

/**
 * Whether one animateur stays on screen under the chosen mode.
 *
 * Both modes only keep people the question was asked of — a seat in the
 * published plan — and who have not answered. « Silencieux depuis N jours »
 * further asks that the last thing sent to them (the publication, or the
 * reminder when one went out) be older than N days: somebody reminded
 * yesterday is not yet worth a second gesture.
 *
 * @param dernierePublicationLe ISO instant of the last publication, `null`
 *                              before the first one — nobody is then silent
 */
export function keptByAcknowledgement(
  mode: ModeAccuses,
  jours: number,
  confirmation: ConfirmationView | undefined,
  dernierePublicationLe: string | null,
  maintenant: Date,
): boolean {
  if (mode === 'tous') {
    return true;
  }
  if (!confirmation || !confirmation.affecte || confirmation.statut === 'CONFIRME') {
    return false;
  }
  if (mode === 'jamais') {
    return true;
  }
  const dernierEnvoi = confirmation.relanceLe ?? dernierePublicationLe;
  if (!dernierEnvoi) {
    return false;
  }
  return maintenant.getTime() - new Date(dernierEnvoi).getTime() > jours * JOUR_MS;
}
