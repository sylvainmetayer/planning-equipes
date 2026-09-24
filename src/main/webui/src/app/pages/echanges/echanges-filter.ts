// The « à arbitrer » view of the swap requests screen: what the home screen's
// « À traiter aujourd'hui » counts, and what its link opens. Kept apart from
// the page so the rule is pinned down without rendering it.

import type { DemandeEchangeView } from '../../core/models';

/** The value of the `statut` query param that narrows the screen to the requests to arbitrate. */
export const TO_ARBITRATE = 'a-arbitrer';

/** Tolerant reading: anything but {@link TO_ARBITRATE} is the whole screen, never an error. */
export function readToArbitrate(statut: string | null): boolean {
  return statut === TO_ARBITRATE;
}

/**
 * Since when a request has waited on the organisation: the colleague's
 * agreement, which is what put it on the desk, or its creation for one that
 * predates the two-step flow — the same reading as the nightly alert's.
 */
export function waitingSince(demande: DemandeEchangeView): string {
  return demande.cibleDecideLe ?? demande.creeLe;
}

/** The requests, the one waiting the longest first. */
export function oldestWaitingFirst<T extends DemandeEchangeView>(demandes: readonly T[]): T[] {
  return [...demandes].sort((a, b) => waitingSince(a).localeCompare(waitingSince(b)));
}
