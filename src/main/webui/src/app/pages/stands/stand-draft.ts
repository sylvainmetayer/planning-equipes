// The rules of editing one stand, kept out of the dialog so they are unit
// tested without rendering — the same convention `stand-bulk-edit.ts` already
// follows for the bulk editor next door (AGENTS.md).
//
// The dialog used to hold all of it: a deep draft object, fifteen methods each
// re-writing the same immutable update by hand, and six validation `computed`s
// carrying the whole entry logic of a stand — 442 lines, zero tests.

import { effectifFenetreInvalide } from '../../core/horaire-stand';
import {
  Emplacement,
  FenetreHoraire,
  HoraireStand,
  IndisponibiliteStand,
  JourSemaine,
  NiveauEffort,
  OuvertureStand,
  Stand,
} from '../../core/models';

/**
 * A rule as the form holds it: the entity, plus what only the editing of it
 * needs to remember. `saisie` is the compact line as typed — kept verbatim
 * while it does not parse, so a half-typed `10:0` is not rewritten under the
 * cursor; `null` once the windows were last edited another way. `deplie`
 * shows the mode and day selectors of a plain rule; `detail` shows the windows
 * as one row of fields each instead of the line.
 */
export interface HoraireDraft extends HoraireStand {
  saisie?: string | null;
  deplie?: boolean;
  detail?: boolean;
}

/** The form's own state: flat where the entity is nested, strings where the inputs are. */
export interface StandDraft {
  id: string;
  nom: string;
  effectifMin: number;
  effectifMax: number;
  reserveMajeurs: boolean;
  premium: boolean;
  niveauEffort: NiveauEffort;
  /** Relay family, `null` = let the server pick the least populated one. */
  typologiesProposees: string[];
  emplacementId: string | null;
  indisponibilites: IndisponibiliteStand[];
  ouvertures: OuvertureStand[];
  horaires: HoraireDraft[];
  /** The store's `modifieLe` at opening, sent back as the write's precondition (issue #362). */
  modifieLe: string | null;
}

/* ------------------------------ list edits ------------------------------ */
//
// Three operations, written once instead of once per list. They return new
// arrays: a signal holding an object mutated in place notifies nobody.

/** Replaces the item at `index` with a patched copy; out-of-range is a no-op. */
export function patchDansListe<T>(liste: readonly T[], index: number, patch: Partial<T>): T[] {
  return liste.map((item, i) => (i === index ? { ...item, ...patch } : item));
}

export function retirerDe<T>(liste: readonly T[], index: number): T[] {
  return liste.filter((_, i) => i !== index);
}

export function ajouterA<T>(liste: readonly T[], item: T): T[] {
  return [...liste, item];
}

export { effectifFenetreInvalide };

/** An empty dated closure. */
export function plageVide(): IndisponibiliteStand {
  return { id: null, date: '', heureDebut: '', heureFin: null, motif: null };
}

/** An empty dated opening: a closure's shape plus the seats it may name. */
export function ouvertureVide(): OuvertureStand {
  return { ...plageVide(), effectif: null };
}

export function fenetreVide(): FenetreHoraire {
  return { heureDebut: '', heureFin: null, effectif: null };
}

/**
 * An emptied `<input type="number">` gives back `null` (or `''` from an older
 * form state): both mean "no effectif named", never a zero.
 */
export function normaliserEffectif(effectif: number | null | undefined | string): number | null {
  return effectif === null || effectif === undefined || effectif === '' ? null : Number(effectif);
}

/** Adds or removes one weekday of a `JOURS_SEMAINE` rule, without duplicates. */
export function basculerJour(
  joursSemaine: readonly JourSemaine[],
  jour: JourSemaine,
  coche: boolean,
): JourSemaine[] {
  return coche
    ? [...new Set([...joursSemaine, jour])]
    : joursSemaine.filter((autre) => autre !== jour);
}

/**
 * The `DATES` scope is typed as comma-separated ISO dates — a plain text field
 * beats seven date pickers. Anything that is not an ISO date is dropped rather
 * than sent to a backend that would reject the whole rule.
 */
export function datesFromText(valeur: string): string[] {
  return valeur
    .split(',')
    .map((date) => date.trim())
    .filter((date) => /^\d{4}-\d{2}-\d{2}$/.test(date));
}

/* ------------------------------ validation ------------------------------ */

export function effectifInvalide(draft: StandDraft): boolean {
  return Number(draft.effectifMax) < Number(draft.effectifMin);
}

/** A stand always carries at least one typologie (issue #343) — the server refuses otherwise, this says it first. */
export function typologiesVides(draft: StandDraft): boolean {
  return (draft.typologiesProposees ?? []).length === 0;
}

/**
 * Any closure missing a date or a start time, or whose end time isn't strictly
 * after its start — the backend rejects these outright. An *empty* end time is
 * valid and means "until closing time".
 */
export function indisponibiliteInvalide(draft: StandDraft): boolean {
  return draft.indisponibilites.some((indispo) => plageInvalide(indispo));
}

/** Same rules as {@link indisponibiliteInvalide}, for the opening exceptions. */
export function ouvertureInvalide(draft: StandDraft): boolean {
  return draft.ouvertures.some((ouverture) => plageInvalide(ouverture));
}

/** An opening naming a zero, negative or fractional effectif — reported apart, it has its own sentence. */
export function effectifOuvertureInvalide(draft: StandDraft): boolean {
  return draft.ouvertures.some((ouverture) =>
    effectifFenetreInvalide(ouverture.effectif, Number(draft.effectifMax)),
  );
}

function plageInvalide(plage: {
  date: string;
  heureDebut: string;
  heureFin: string | null;
}): boolean {
  return (
    !plage.date || !plage.heureDebut || (!!plage.heureFin && plage.heureFin <= plage.heureDebut)
  );
}

/** A day can't carry both a closure and an opening — the backend rejects this outright. */
export function conflitOuvertureFermeture(draft: StandDraft): boolean {
  const joursFermeture = new Set(
    draft.indisponibilites.map((indispo) => indispo.date).filter(Boolean),
  );
  return draft.ouvertures.some((ouverture) => ouverture.date && joursFermeture.has(ouverture.date));
}

/**
 * Everything wrong with the draft that does not need a translated message.
 * The recurring rules are checked separately, by `erreurHoraire` in
 * `core/horaire-stand.ts`, because their outcome *is* a sentence to display.
 */
export function brouillonInvalide(draft: StandDraft): boolean {
  return (
    typologiesVides(draft) ||
    effectifInvalide(draft) ||
    indisponibiliteInvalide(draft) ||
    ouvertureInvalide(draft) ||
    conflitOuvertureFermeture(draft)
  );
}

/* --------------------------- entity <-> draft --------------------------- */

export function toDraft(stand: Stand | null): StandDraft {
  if (!stand) {
    return {
      id: '',
      nom: '',
      effectifMin: 1,
      effectifMax: 1,
      reserveMajeurs: false,
      premium: false,
      niveauEffort: 'NORMAL',
      typologiesProposees: [],
      emplacementId: null,
      indisponibilites: [],
      ouvertures: [],
      horaires: [],
      modifieLe: null,
    };
  }
  return {
    id: stand.id,
    modifieLe: stand.modifieLe ?? null,
    nom: stand.nom ?? '',
    effectifMin: stand.effectifMin,
    effectifMax: stand.effectifMax,
    reserveMajeurs: Boolean(stand.reserveMajeurs),
    premium: Boolean(stand.premium),
    niveauEffort: stand.niveauEffort ?? 'NORMAL',
    typologiesProposees: [...(stand.typologiesProposees ?? [])],
    emplacementId: stand.emplacement?.id ?? null,
    // Copied, never aliased: the draft is edited in place by the form and must
    // not write through to the store's own objects.
    indisponibilites: (stand.indisponibilites ?? []).map((indispo) => ({ ...indispo })),
    ouvertures: (stand.ouvertures ?? []).map((ouverture) => ({ ...ouverture })),
    horaires: (stand.horaires ?? []).map((horaire) => ({
      ...horaire,
      joursSemaine: [...horaire.joursSemaine],
      dates: [...horaire.dates],
      fenetres: horaire.fenetres.map((fenetre) => ({ ...fenetre })),
    })),
  };
}

/** The entity to send, resolved against the emplacements the store knows. */
export function versStand(draft: StandDraft, emplacements: readonly Emplacement[]): Stand {
  return {
    id: draft.id.trim(),
    nom: draft.nom.trim(),
    typologiesProposees: draft.typologiesProposees,
    effectifMin: Number(draft.effectifMin) || 0,
    effectifMax: Number(draft.effectifMax) || 0,
    reserveMajeurs: draft.reserveMajeurs,
    premium: draft.premium,
    niveauEffort: draft.niveauEffort,
    emplacement: draft.emplacementId
      ? (emplacements.find((emplacement) => emplacement.id === draft.emplacementId) ?? null)
      : null,
    indisponibilites: draft.indisponibilites.map(normaliserPlage),
    ouvertures: draft.ouvertures.map((ouverture) => ({
      ...normaliserPlage(ouverture),
      effectif: normaliserEffectif(ouverture.effectif),
    })),
    horaires: draft.horaires.map(normaliserHoraire),
    modifieLe: draft.modifieLe,
  };
}

/**
 * An emptied `<input type="time">` gives back `''`, not `null` — and `''` would
 * reach the backend as a malformed time rather than as "until closing time".
 */
export function normaliserPlage<T extends { heureFin: string | null }>(plage: T): T {
  return { ...plage, heureFin: plage.heureFin || null };
}

export function normaliserHoraire(horaire: HoraireDraft): HoraireStand {
  // The editing state stays in the form: the entity has no such fields.
  const { saisie: _saisie, deplie: _deplie, detail: _detail, ...entite } = horaire;
  return {
    ...entite,
    fenetres: horaire.fenetres.map((fenetre) => ({
      ...fenetre,
      heureFin: fenetre.heureFin || null,
      effectif: normaliserEffectif(fenetre.effectif),
    })),
    // Only the fields the chosen scope uses are sent, so a rule switched from
    // PLAGE to TOUS doesn't keep dragging its old bounds along.
    joursSemaine: horaire.jours === 'JOURS_SEMAINE' ? horaire.joursSemaine : [],
    dateDebut: horaire.jours === 'PLAGE' ? horaire.dateDebut : null,
    dateFin: horaire.jours === 'PLAGE' ? horaire.dateFin : null,
    dates: horaire.jours === 'DATES' ? horaire.dates : [],
  };
}
