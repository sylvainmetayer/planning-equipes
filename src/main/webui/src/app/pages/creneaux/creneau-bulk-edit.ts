// What a bulk edit of créneaux can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without rendering.

import { Creneau } from '../../core/models';

export interface CreneauBulkPatch {
  /** Empty string leaves each créneau's own hour alone. */
  heureDebut: string;
  heureFin: string;
}

export function patchCreneauVide(): CreneauBulkPatch {
  return { heureDebut: '', heureFin: '' };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchCreneauEstVide(patch: CreneauBulkPatch): boolean {
  return !patch.heureDebut && !patch.heureFin;
}

export function appliquerPatchCreneau(creneau: Creneau, patch: CreneauBulkPatch): Creneau {
  return {
    ...creneau,
    heureDebut: patch.heureDebut || creneau.heureDebut,
    heureFin: patch.heureFin || creneau.heureFin,
  };
}

/**
 * Créneaux the patch would leave running past midnight. Changing only one of the
 * two hours is legitimate, but the result has to be read against each slot's own
 * other hour — hence the count per row rather than on the form alone.
 *
 * These are reported, not refused: an end at or before the start is how the
 * domain writes a night slot (`Creneau.getDureeMinutes` counts 20:00→00:00 as
 * 240 minutes). Refusing them made the night slot unreachable in bulk, which is
 * precisely where it is tedious to enter one by one — but a batch is worth
 * naming, since nobody re-reads sixty rows before confirming.
 */
export function creneauxFranchissantMinuit(
  creneaux: readonly Creneau[],
  patch: CreneauBulkPatch,
): Creneau[] {
  return creneaux.filter((creneau) => {
    const resultat = appliquerPatchCreneau(creneau, patch);
    return resultat.heureFin <= resultat.heureDebut;
  });
}
