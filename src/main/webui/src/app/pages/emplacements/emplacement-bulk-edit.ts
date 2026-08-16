// What a bulk edit of emplacements can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without Leaflet.

import { Emplacement } from '../../core/models';

/** `DEFINIR` puts every selected place on the same point, `EFFACER` unsets them all. */
export type ModeCoordonnees = 'INCHANGE' | 'DEFINIR' | 'EFFACER';

export interface EmplacementBulkPatch {
  coordonnees: { mode: ModeCoordonnees; latitude: number | null; longitude: number | null };
}

export function patchEmplacementVide(): EmplacementBulkPatch {
  return { coordonnees: { mode: 'INCHANGE', latitude: null, longitude: null } };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchEmplacementEstVide(patch: EmplacementBulkPatch): boolean {
  const { mode, latitude, longitude } = patch.coordonnees;
  if (mode === 'INCHANGE') {
    return true;
  }
  return mode === 'DEFINIR' && (latitude === null || longitude === null);
}

export function appliquerPatchEmplacement(emplacement: Emplacement, patch: EmplacementBulkPatch): Emplacement {
  const { mode, latitude, longitude } = patch.coordonnees;
  if (mode === 'EFFACER') {
    return { ...emplacement, latitude: null, longitude: null };
  }
  if (mode === 'INCHANGE' || latitude === null || longitude === null) {
    return { ...emplacement };
  }
  return { ...emplacement, latitude: Number(latitude), longitude: Number(longitude) };
}
