// The « en attente » view of the declarations screen: what the home screen's
// « À traiter aujourd'hui » counts, and what its link opens. Kept apart from
// the page so the rule is pinned down without rendering it.

import type { DeclarationAdminView } from '../../core/models';

/** The value of the `statut` query param that narrows the screen to the pending declarations. */
export const PENDING = 'en-attente';

/** Tolerant reading: anything but {@link PENDING} is the whole screen, never an error. */
export function readPendingOnly(statut: string | null): boolean {
  return statut === PENDING;
}

/** The declarations, the one received first on top. */
export function oldestFirst<T extends DeclarationAdminView>(declarations: readonly T[]): T[] {
  return [...declarations].sort((a, b) => a.creeLe.localeCompare(b.creeLe));
}
