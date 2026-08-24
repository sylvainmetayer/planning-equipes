// What a bulk edit of stands can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without rendering.

import { ModeBooleen, ModeListe, appliquerModeBooleen, appliquerModeListe } from '../../core/bulk-edit';
import { Emplacement, HoraireStand, NiveauEffort, Stand } from '../../core/models';

/** `DEFINIR` ties every selected stand to one emplacement, `EFFACER` unties them all. */
export type ModeEmplacement = 'INCHANGE' | 'DEFINIR' | 'EFFACER';

/**
 * How the recurring horaires of the selection are edited. This is the reason a
 * bulk edit is worth having for them at all: on the reference event, thirty
 * stands share the single rule "open from 14:00 to closing, every day", and
 * `REMPLACER` sets all thirty in one operation.
 *
 * `AJOUTER` appends the rules to whatever each stand already has; `REMPLACER`
 * discards each stand's own rules first; `EFFACER` drops them all, which returns
 * every stand to its dated exceptions (or to open-all-day if it has none).
 */
export type ModeHoraires = 'INCHANGE' | 'AJOUTER' | 'REMPLACER' | 'EFFACER';

export interface StandBulkPatch {
  emplacement: { mode: ModeEmplacement; emplacementId: string | null };
  typologies: { mode: ModeListe; typologies: string[] };
  /** `null` leaves each stand's own bound alone. */
  effectifMin: number | null;
  effectifMax: number | null;
  reserveMajeurs: ModeBooleen;
  premium: ModeBooleen;
  niveauEffort: 'INCHANGE' | NiveauEffort;
  horaires: { mode: ModeHoraires; horaires: HoraireStand[] };
}

export function patchStandVide(): StandBulkPatch {
  return {
    emplacement: { mode: 'INCHANGE', emplacementId: null },
    typologies: { mode: 'AUCUN', typologies: [] },
    effectifMin: null,
    effectifMax: null,
    reserveMajeurs: 'INCHANGE',
    premium: 'INCHANGE',
    niveauEffort: 'INCHANGE',
    horaires: { mode: 'INCHANGE', horaires: [] }
  };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchStandEstVide(patch: StandBulkPatch): boolean {
  const emplacementInactif =
    patch.emplacement.mode === 'INCHANGE' || (patch.emplacement.mode === 'DEFINIR' && !patch.emplacement.emplacementId);
  const typologiesInactives =
    patch.typologies.mode === 'AUCUN' ||
    (patch.typologies.typologies.length === 0 && patch.typologies.mode !== 'REMPLACER');
  const horairesInactifs =
    patch.horaires.mode === 'INCHANGE' ||
    (patch.horaires.mode !== 'EFFACER' && patch.horaires.horaires.length === 0);
  return (
    emplacementInactif &&
    typologiesInactives &&
    patch.effectifMin === null &&
    patch.effectifMax === null &&
    patch.reserveMajeurs === 'INCHANGE' &&
    patch.premium === 'INCHANGE' &&
    patch.niveauEffort === 'INCHANGE' &&
    horairesInactifs
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
    niveauEffort: patch.niveauEffort === 'INCHANGE' ? (stand.niveauEffort ?? 'NORMAL') : patch.niveauEffort,
    horaires: appliquerHoraires(stand.horaires ?? [], patch.horaires)
  };
}

/**
 * The rules are copied per stand rather than shared: they carry a persisted `id`
 * per stand, so handing the same objects to fifty payloads would send fifty
 * stands the id of one of them.
 */
function appliquerHoraires(actuels: readonly HoraireStand[], patch: StandBulkPatch['horaires']): HoraireStand[] {
  switch (patch.mode) {
    case 'INCHANGE':
      return [...actuels];
    case 'EFFACER':
      return [];
    case 'REMPLACER':
      return patch.horaires.map(copierHoraire);
    case 'AJOUTER':
      return [...actuels, ...patch.horaires.map(copierHoraire)];
  }
}

function copierHoraire(horaire: HoraireStand): HoraireStand {
  return {
    ...horaire,
    id: null,
    joursSemaine: [...horaire.joursSemaine],
    dates: [...horaire.dates],
    fenetres: horaire.fenetres.map((fenetre) => ({ ...fenetre }))
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
