// What a referential id is called on screen.
//
// The ids of stands, typologies, emplacements and animateurs are drawn by the
// server per edition (`S12`, `T3`, `A140`): they name nothing to a reader. A
// screen that receives one resolves it here, against the rows it already
// holds (usually `ReferenceDataStore`), and falls back to the id only when the
// row is unknown — deleted since, or not loaded yet — so a line never goes
// blank. Typologies already have theirs in `typologie-colors.ts`
// (`typologieLabels` / `typologieLabel`), which this file does not duplicate.

import { Animateur, Creneau, Emplacement, Stand } from './models';

/** Display name by id; built once per list, read many times. */
export type LabelIndex = ReadonlyMap<string, string>;

/** « Prénom Nom », trimmed; empty when the fiche carries neither. */
export function animateurName(animateur: Pick<Animateur, 'prenom' | 'nom'>): string {
  return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim();
}

/**
 * A créneau by its day and hours (« 2026-07-14 10:00–12:00 »): it has no name,
 * and its numeric id tells a reader nothing.
 */
export function creneauName(creneau: Pick<Creneau, 'date' | 'heureDebut' | 'heureFin'>): string {
  return `${creneau.date} ${creneau.heureDebut}–${creneau.heureFin}`;
}

/** Index of rows by id, keeping only the ones whose label is not blank. */
function indexBy<T extends { id: string }>(
  rows: readonly T[],
  label: (row: T) => string | null | undefined,
): LabelIndex {
  const index = new Map<string, string>();
  for (const row of rows) {
    const text = label(row)?.trim();
    if (text) {
      index.set(row.id, text);
    }
  }
  return index;
}

export function standNames(stands: readonly Stand[]): LabelIndex {
  return indexBy(stands, (stand) => stand.nom);
}

export function emplacementNames(emplacements: readonly Emplacement[]): LabelIndex {
  return indexBy(emplacements, (emplacement) => emplacement.nom);
}

export function animateurNames(animateurs: readonly Animateur[]): LabelIndex {
  return indexBy(animateurs, animateurName);
}

/** The label of an id, or the id itself when the index does not know it. */
export function labelOf(index: LabelIndex, id: string): string {
  return index.get(id) ?? id;
}

/** {@link labelOf} over a list, order kept. */
export function labelsOf(index: LabelIndex, ids: readonly string[]): string[] {
  return ids.map((id) => labelOf(index, id));
}
