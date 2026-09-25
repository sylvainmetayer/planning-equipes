// Draft of the covoiturage request (« Je viens avec… »): who the animateur
// has picked before sending. Pure functions, so the rules — where the picker
// opens from, what counts as a change, what actually leaves — are unit-tested
// without rendering the tab.

import { CarpoolEspaceView, NewCarpoolRequest } from '../../core/models';
import { compareCodeUnits } from '../../core/string-order';

/** A car: the animateur and three teammates at most — beyond, it is a shuttle. */
export const CARPOOL_MAX = 3;

/** True while a validated grouped arrival holds the animateur: the tab then only shows it. */
export function carpoolLocked(view: CarpoolEspaceView | null): boolean {
  return view?.status === 'VALIDEE';
}

/**
 * The teammates the pending request names — the reference a new request is
 * compared with. A request set aside, or a car the organisation cancelled,
 * is decided: it is not what the organisation is holding any more, so the
 * picker starts blank after it.
 */
export function pendingTeammates(view: CarpoolEspaceView | null): string[] {
  return view?.status === 'EN_ATTENTE' ? [...view.teammateIds] : [];
}

/** Where the picker opens from: the pending request, else nobody. */
export function initialTeammates(view: CarpoolEspaceView | null): string[] {
  return pendingTeammates(view);
}

/**
 * True when the draft says something other than the pending request.
 * Resending the same car would ask the organisation nothing new, so the button
 * stays disabled; emptying a pending car is a change — it withdraws it.
 */
export function carpoolModified(view: CarpoolEspaceView | null, draft: readonly string[]): boolean {
  if (!view || carpoolLocked(view)) {
    return false;
  }
  const reference = [...pendingTeammates(view)].sort(compareCodeUnits);
  const sorted = [...draft].sort(compareCodeUnits);
  return reference.length !== sorted.length || sorted.some((id, index) => id !== reference[index]);
}

/** What actually leaves for the backend, sorted the way two equal drafts compare equal. */
export function toCarpoolRequest(draft: readonly string[]): NewCarpoolRequest {
  return { teammateIds: [...draft].sort(compareCodeUnits) };
}

/**
 * Adds or removes one teammate. Never more than {@link CARPOOL_MAX}: a fourth
 * one is ignored rather than dropping one already chosen behind the reader's
 * back.
 */
export function toggleTeammate(draft: readonly string[], animateurId: string): string[] {
  if (draft.includes(animateurId)) {
    return draft.filter((candidate) => candidate !== animateurId);
  }
  return draft.length >= CARPOOL_MAX ? [...draft] : [...draft, animateurId];
}

/**
 * The day the window opens, when it is closed only because it has not started
 * yet — `null` when it is open, or closed for good. The same reading as the
 * declaration tab: « come back later » and « too late » are one boolean on the
 * server and the opposite to whoever reads it.
 *
 * @param today ISO date of today, passed in so the rule is testable without a clock
 */
export function openingAhead(view: CarpoolEspaceView | null, today: string): string | null {
  if (!view || view.collectionOpen || !view.collectionStart) {
    return null;
  }
  return view.collectionStart > today ? view.collectionStart : null;
}
