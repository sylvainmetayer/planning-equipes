// What a bulk edit of stands can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without rendering.

import { ModeBooleen, ModeListe, appliquerModeBooleen, appliquerModeListe } from '../../core/bulk-edit';
import { Emplacement, NiveauEffort, Stand } from '../../core/models';

/** `DEFINIR` ties every selected stand to one emplacement, `EFFACER` unties them all. */
export type ModeEmplacement = 'INCHANGE' | 'DEFINIR' | 'EFFACER';

export interface StandBulkPatch {
  emplacement: { mode: ModeEmplacement; emplacementId: string | null };
  typologies: { mode: ModeListe; typologies: string[] };
  /** `null` leaves each stand's own bound alone. */
  effectifMin: number | null;
  effectifMax: number | null;
  reserveMajeurs: ModeBooleen;
  premium: ModeBooleen;
  niveauEffort: 'INCHANGE' | NiveauEffort;
}

export function patchStandVide(): StandBulkPatch {
  return {
    emplacement: { mode: 'INCHANGE', emplacementId: null },
    typologies: { mode: 'AUCUN', typologies: [] },
    effectifMin: null,
    effectifMax: null,
    reserveMajeurs: 'INCHANGE',
    premium: 'INCHANGE',
    niveauEffort: 'INCHANGE'
  };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchStandEstVide(patch: StandBulkPatch): boolean {
  const emplacementInactif =
    patch.emplacement.mode === 'INCHANGE' || (patch.emplacement.mode === 'DEFINIR' && !patch.emplacement.emplacementId);
  const typologiesInactives =
    patch.typologies.mode === 'AUCUN' ||
    (patch.typologies.typologies.length === 0 && patch.typologies.mode !== 'REMPLACER');
  return (
    emplacementInactif &&
    typologiesInactives &&
    patch.effectifMin === null &&
    patch.effectifMax === null &&
    patch.reserveMajeurs === 'INCHANGE' &&
    patch.premium === 'INCHANGE' &&
    patch.niveauEffort === 'INCHANGE'
  );
}

export function appliquerPatchStand(
  stand: Stand,
  patch: StandBulkPatch,
  emplacements: readonly Emplacement[]
): Stand {
  return {
    ...stand,
    emplacement: appliquerEmplacement(stand.emplacement ?? null, patch.emplacement, emplacements),
    typologiesProposees: appliquerModeListe(
      stand.typologiesProposees ?? [],
      patch.typologies.typologies,
      patch.typologies.mode
    ),
    effectifMin: patch.effectifMin ?? stand.effectifMin,
    effectifMax: patch.effectifMax ?? stand.effectifMax,
    reserveMajeurs: appliquerModeBooleen(Boolean(stand.reserveMajeurs), patch.reserveMajeurs),
    premium: appliquerModeBooleen(Boolean(stand.premium), patch.premium),
    niveauEffort: patch.niveauEffort === 'INCHANGE' ? (stand.niveauEffort ?? 'NORMAL') : patch.niveauEffort
  };
}

/**
 * Stands the patch would leave with `effectifMax < effectifMin` — the backend
 * rejects those one by one, so the dialog blocks the whole batch instead of
 * writing half of it.
 */
export function standsAvecEffectifInvalide(
  stands: readonly Stand[],
  patch: StandBulkPatch,
  emplacements: readonly Emplacement[]
): Stand[] {
  return stands.filter((stand) => {
    const resultat = appliquerPatchStand(stand, patch, emplacements);
    return Number(resultat.effectifMax) < Number(resultat.effectifMin);
  });
}

function appliquerEmplacement(
  actuel: Emplacement | null,
  patch: StandBulkPatch['emplacement'],
  emplacements: readonly Emplacement[]
): Emplacement | null {
  if (patch.mode === 'EFFACER') {
    return null;
  }
  if (patch.mode === 'INCHANGE' || !patch.emplacementId) {
    return actuel;
  }
  return emplacements.find((emplacement) => emplacement.id === patch.emplacementId) ?? actuel;
}
