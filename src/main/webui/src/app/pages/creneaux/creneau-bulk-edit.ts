// What a bulk edit of créneaux can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without rendering.

import { Creneau, GroupeCreneau } from '../../core/models';

export interface CreneauBulkPatch {
  /** `null` keeps each créneau in its own group; otherwise every selected slot moves there. */
  groupeId: string | null;
  /** Empty string leaves each créneau's own hour alone. */
  heureDebut: string;
  heureFin: string;
}

export function patchCreneauVide(): CreneauBulkPatch {
  return { groupeId: null, heureDebut: '', heureFin: '' };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchCreneauEstVide(patch: CreneauBulkPatch): boolean {
  return patch.groupeId === null && !patch.heureDebut && !patch.heureFin;
}

export function appliquerPatchCreneau(
  creneau: Creneau,
  patch: CreneauBulkPatch,
  groupes: readonly GroupeCreneau[]
): Creneau {
  return {
    ...creneau,
    heureDebut: patch.heureDebut || creneau.heureDebut,
    heureFin: patch.heureFin || creneau.heureFin,
    groupe: patch.groupeId ? (groupes.find((groupe) => groupe.id === patch.groupeId) ?? creneau.groupe) : creneau.groupe
  };
}

/**
 * Créneaux the patch would leave ending before they start. Changing only one of
 * the two hours is legitimate, but it has to stay coherent with each slot's own
 * other hour — hence the check per row rather than on the form alone.
 */
export function creneauxAvecHorairesInvalides(
  creneaux: readonly Creneau[],
  patch: CreneauBulkPatch,
  groupes: readonly GroupeCreneau[]
): Creneau[] {
  return creneaux.filter((creneau) => {
    const resultat = appliquerPatchCreneau(creneau, patch, groupes);
    return resultat.heureFin <= resultat.heureDebut;
  });
}
