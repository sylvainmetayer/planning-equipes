// What a bulk edit of stands can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without rendering.

import {
  ModeBooleen,
  ModeListe,
  appliquerModeBooleen,
  appliquerModeListe,
} from '../../core/bulk-edit';
import { Emplacement, HoraireStand, NiveauEffort, Stand } from '../../core/models';
import {
  hasHorairesToCopy,
  HoraireDraft,
  HorairesDraft,
  horairesCopiedFrom,
  windowsBeyondMaximumCount,
  normaliserHoraire,
} from './stand-draft';

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
 *
 * `DEPUIS_STAND` is the typical-day gesture: every selected stand takes the
 * whole schedule of one model stand — rules **and** dated exceptions, window
 * effectifs included — where the three modes above only touch the rules.
 */
export type ModeHoraires = 'INCHANGE' | 'AJOUTER' | 'REMPLACER' | 'EFFACER' | 'DEPUIS_STAND';

/** The horaires part of a patch: the typed rules, or the stand to copy from. */
export interface PatchHoraires {
  mode: ModeHoraires;
  horaires: HoraireDraft[];
  /** The model stand of `DEPUIS_STAND`; `null` until one is chosen, which leaves the patch inert. */
  source: Stand | null;
}

export interface StandBulkPatch {
  emplacement: { mode: ModeEmplacement; emplacementId: number | null };
  typologies: { mode: ModeListe; typologies: string[] };
  /** `null` leaves each stand's own bound alone. */
  effectifMin: number | null;
  effectifMax: number | null;
  reserveMajeurs: ModeBooleen;
  premium: ModeBooleen;
  niveauEffort: 'INCHANGE' | NiveauEffort;
  horaires: PatchHoraires;
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
    horaires: { mode: 'INCHANGE', horaires: [], source: null },
  };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchStandEstVide(patch: StandBulkPatch): boolean {
  const emplacementInactif =
    patch.emplacement.mode === 'INCHANGE' ||
    (patch.emplacement.mode === 'DEFINIR' && !patch.emplacement.emplacementId);
  const typologiesInactives =
    patch.typologies.mode === 'AUCUN' ||
    (patch.typologies.typologies.length === 0 && patch.typologies.mode !== 'REMPLACER');
  const horairesInactifs = patchHorairesIsEmpty(patch.horaires);
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

/** True while the horaires part would change nothing: no rule typed, or no model stand chosen. */
function patchHorairesIsEmpty(patch: PatchHoraires): boolean {
  switch (patch.mode) {
    case 'INCHANGE':
      return true;
    case 'EFFACER':
      return false;
    case 'DEPUIS_STAND':
      // A model with nothing to copy changes nothing: erasing is a mode of its own.
      return patch.source === null || !hasHorairesToCopy(patch.source);
    case 'AJOUTER':
    case 'REMPLACER':
      return patch.horaires.length === 0;
  }
}

export function appliquerPatchStand(
  stand: Stand,
  patch: StandBulkPatch,
  emplacements: readonly Emplacement[],
): Stand {
  return {
    ...stand,
    ...applyExceptions(patch.horaires),
    emplacement: appliquerEmplacement(stand.emplacement ?? null, patch.emplacement, emplacements),
    typologiesProposees: appliquerModeListe(
      stand.typologiesProposees ?? [],
      patch.typologies.typologies,
      patch.typologies.mode,
    ),
    effectifMin: patch.effectifMin ?? stand.effectifMin,
    effectifMax: patch.effectifMax ?? stand.effectifMax,
    reserveMajeurs: appliquerModeBooleen(Boolean(stand.reserveMajeurs), patch.reserveMajeurs),
    premium: appliquerModeBooleen(Boolean(stand.premium), patch.premium),
    niveauEffort:
      patch.niveauEffort === 'INCHANGE' ? (stand.niveauEffort ?? 'NORMAL') : patch.niveauEffort,
    horaires: applyHoraires(stand.horaires ?? [], patch.horaires),
  };
}

/**
 * The rules are copied per stand rather than shared: they carry a persisted `id`
 * per stand, so handing the same objects to fifty payloads would send fifty
 * stands the id of one of them.
 */
function applyHoraires(actuels: readonly HoraireStand[], patch: PatchHoraires): HoraireStand[] {
  switch (patch.mode) {
    case 'INCHANGE':
      return [...actuels];
    case 'EFFACER':
      return [];
    case 'REMPLACER':
      return patch.horaires.map(copierHoraire);
    case 'AJOUTER':
      return [...actuels, ...patch.horaires.map(copierHoraire)];
    case 'DEPUIS_STAND':
      return patch.source === null
        ? [...actuels]
        : (patch.source.horaires ?? []).map(copierHoraire);
  }
}

/**
 * The dated exceptions of the model stand, ids reset, in `DEPUIS_STAND` mode
 * — copying a typical day means its openings and closures too. Every other
 * mode leaves each stand's own exceptions alone, hence the empty patch.
 */
function applyExceptions(
  patch: PatchHoraires,
): Pick<Stand, 'ouvertures' | 'indisponibilites'> | Record<string, never> {
  if (patch.mode !== 'DEPUIS_STAND' || patch.source === null) {
    return {};
  }
  const { ouvertures, indisponibilites } = horairesCopiedFrom(patch.source);
  return { ouvertures, indisponibilites };
}

function copierHoraire(horaire: HoraireDraft): HoraireStand {
  // Normalised like the single-stand form: the editing state (the typed line,
  // the fold flags) stays behind, and an emptied end reads as "until closing".
  //
  // The two arrays are copied from the *normalised* rule, never from the draft:
  // read from the draft they brought back the days a scope switched away from
  // JOURS_SEMAINE was still dragging along, which the solver ignores but the
  // write-time warnings read as a schedule change.
  const normalise = normaliserHoraire(horaire);
  return {
    ...normalise,
    id: null,
    joursSemaine: [...normalise.joursSemaine],
    dates: [...normalise.dates],
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
  emplacements: readonly Emplacement[],
): Stand[] {
  return stands.filter((stand) => {
    const resultat = appliquerPatchStand(stand, patch, emplacements);
    return Number(resultat.effectifMax) < Number(resultat.effectifMin);
  });
}

/**
 * Stands the patch would leave with a window asking for more seats than their
 * `effectifMax` — the server refuses each such stand, so the dialog says which
 * ones before the batch is sent. Not blocking: raising the maximum in the same
 * bulk edit is the usual fix, and this reads the patched value.
 */
export function standsWithWindowBeyondMaximum(
  stands: readonly Stand[],
  patch: StandBulkPatch,
  emplacements: readonly Emplacement[],
): Stand[] {
  return stands.filter((stand) => {
    const resultat = appliquerPatchStand(stand, patch, emplacements);
    const horaires: Pick<HorairesDraft, 'horaires' | 'ouvertures'> = {
      horaires: resultat.horaires,
      ouvertures: resultat.ouvertures ?? [],
    };
    return windowsBeyondMaximumCount(horaires, Number(resultat.effectifMax)) > 0;
  });
}

function appliquerEmplacement(
  actuel: Emplacement | null,
  patch: StandBulkPatch['emplacement'],
  emplacements: readonly Emplacement[],
): Emplacement | null {
  if (patch.mode === 'EFFACER') {
    return null;
  }
  if (patch.mode === 'INCHANGE' || !patch.emplacementId) {
    return actuel;
  }
  return emplacements.find((emplacement) => emplacement.id === patch.emplacementId) ?? actuel;
}
