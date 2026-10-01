// The acknowledgement filters of the Animateurs page (issue #504), as a pure
// function of one row's answer: « jamais confirmés », « silencieux depuis
// N jours » and « échec d'envoi » — somebody whose last mail failed was not
// silent, they were not reached. Kept apart from the page so the rule — and
// the day arithmetic — is pinned down without rendering a table.

import type { ConfirmationView } from '../../core/models';

/** What the « Accusés » select of the page offers. */
export type ModeAccuses = 'tous' | 'jamais' | 'silence' | 'echec';

/** The number of days the « silencieux depuis » control opens on. */
export const SILENCE_JOURS_DEFAUT = 3;

const JOUR_MS = 24 * 60 * 60 * 1000;

/**
 * Reads the three URL params. `silence` wins when it carries a positive whole
 * number of days; `envoi=echec` next, then `confirmation=jamais`; anything else
 * is the default, never an error — an old link degrades to the whole table.
 */
export function readModeAccuses(
  confirmation: string | null,
  silence: string | null,
  envoi: string | null = null,
): { mode: ModeAccuses; jours: number } {
  const jours = silence === null ? Number.NaN : Number(silence);
  if (Number.isInteger(jours) && jours > 0) {
    return { mode: 'silence', jours };
  }
  if (envoi === 'echec') {
    return { mode: 'echec', jours: SILENCE_JOURS_DEFAUT };
  }
  if (confirmation === 'jamais') {
    return { mode: 'jamais', jours: SILENCE_JOURS_DEFAUT };
  }
  return { mode: 'tous', jours: SILENCE_JOURS_DEFAUT };
}

/**
 * Reads the « jamais relancés » criterion from its URL param: `relance=jamais`
 * and nothing else — any other value is the default, never an error.
 */
export function readNeverReminded(relance: string | null): boolean {
  return relance === 'jamais';
}

/**
 * Somebody who has not confirmed and whose last mail failed, with no edit of
 * their fiche since — what the column shows as « échec d'envoi » rather than
 * as a silence, seat or not: an invitation that bounced says so too. The
 * server's count (`echecsEnvoi`) and the « Échec d'envoi » filter only keep
 * the people holding a seat among them.
 */
export function failedSend(confirmation: ConfirmationView | undefined): boolean {
  return (
    confirmation !== undefined &&
    confirmation.statut !== 'CONFIRME' &&
    confirmation.dernierEnvoi?.enEchec === true
  );
}

/**
 * Whether one animateur stays on screen under the chosen mode.
 *
 * « Jamais confirmés » and « silencieux depuis N jours » only keep people the
 * question was asked of — a seat in the published plan — and who have not
 * answered. « Silencieux depuis N jours » further asks that the last thing
 * sent to them (the publication, or the reminder when one went out) be older
 * than N days: somebody reminded yesterday is not yet worth a second gesture —
 * and that it reached them: a failed send is not a silence.
 *
 * « Échec d'envoi » keeps whoever holds a seat, has not confirmed and whose
 * last mail failed ({@link failedSend}): exactly the people the home screen
 * counts as `echecsEnvoi`, whose link opens this filter — a count of three
 * must open on three rows.
 *
 * « Jamais relancés », on top of either mode, drops whoever a reminder already
 * reached: what the home screen counts as « silencieux à relancer » is the
 * silent nobody has chased yet, and its link opens exactly that list.
 *
 * @param dernierePublicationLe ISO instant of the last publication, `null`
 *                              before the first one — nobody is then silent
 * @param neverReminded         keep only the people no reminder went to
 */
export function keptByAcknowledgement(
  mode: ModeAccuses,
  jours: number,
  confirmation: ConfirmationView | undefined,
  dernierePublicationLe: string | null,
  maintenant: Date,
  neverReminded = false,
): boolean {
  if (mode === 'tous') {
    return true;
  }
  if (!confirmation || confirmation.statut === 'CONFIRME') {
    return false;
  }
  if (neverReminded && (confirmation.statut === 'RELANCE' || confirmation.relanceLe !== null)) {
    return false;
  }
  if (!confirmation.affecte) {
    return false;
  }
  if (mode === 'echec') {
    return failedSend(confirmation);
  }
  if (mode === 'jamais') {
    return true;
  }
  if (failedSend(confirmation)) {
    return false;
  }
  const dernierEnvoi = confirmation.relanceLe ?? dernierePublicationLe;
  if (!dernierEnvoi) {
    return false;
  }
  return maintenant.getTime() - new Date(dernierEnvoi).getTime() > jours * JOUR_MS;
}
