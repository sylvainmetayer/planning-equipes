// The entry side of the "Compétences" screen: one appreciation level per
// animateur and typologie, typed with the keys 0-3 or cycled with a click.
// Pure functions, so the moves — a key, a cycle, a save body — are tested
// without rendering; the component only wires them to the DOM.
//
// The cells are held apart from the roster they were read from: the roster is
// what the server says, the cells are what the user is about to say. An
// animateur whose cells differ from their fiche is "modified", and only those
// are sent back — each with their whole map, since a save replaces the
// animateur's appreciations in full, exactly as the fiche form does.

import { correspondAuFiltre } from '../../core/text-filter';
import { Animateur, NiveauCompetence, SaisieAnimateurCompetences } from '../../core/models';

/** A cell's address: the animateur row, and the typologie column. */
export interface CompetenceAddress {
  animateurId: string;
  typologieId: string;
}

/** `animateurId` → `typologieId` → level, `null` for no appreciation. */
export type CellulesCompetences = Map<string, Map<string, NiveauCompetence | null>>;

/** The levels in the order of the keys 1, 2, 3 — and of a click cycling through them. */
export const NIVEAUX: readonly NiveauCompetence[] = ['DEBUTANT', 'AUTONOME', 'REFERENT'];

export function cellKey(animateurId: string, typologieId: string): string {
  return `${animateurId}#${typologieId}`;
}

/**
 * The cells as the roster reports them — the starting point, and the
 * reference a change is measured against. Every typologie of the referential
 * gets a cell, shown or not: a save sends the whole row.
 */
export function cellsFrom(
  animateurs: readonly Animateur[],
  typologieIds: readonly string[],
): CellulesCompetences {
  const cells: CellulesCompetences = new Map();
  for (const animateur of animateurs) {
    const row = new Map<string, NiveauCompetence | null>();
    for (const typologieId of typologieIds) {
      row.set(typologieId, animateur.competences?.[typologieId] ?? null);
    }
    cells.set(animateur.id, row);
  }
  return cells;
}

/** The level of one cell, `null` when the animateur has no appreciation there. */
export function levelAt(
  cells: CellulesCompetences,
  adresse: CompetenceAddress,
): NiveauCompetence | null {
  return cells.get(adresse.animateurId)?.get(adresse.typologieId) ?? null;
}

/** A copy of `cells` with one cell changed; the map is never mutated, so a signal holding it notifies. */
export function writeCell(
  cells: CellulesCompetences,
  adresse: CompetenceAddress,
  niveau: NiveauCompetence | null,
): CellulesCompetences {
  const copy = new Map(cells);
  const row = new Map(copy.get(adresse.animateurId) ?? []);
  row.set(adresse.typologieId, niveau);
  copy.set(adresse.animateurId, row);
  return copy;
}

/** Whether one cell differs from the reference. */
export function isCellModified(
  cells: CellulesCompetences,
  reference: CellulesCompetences,
  adresse: CompetenceAddress,
): boolean {
  return levelAt(cells, adresse) !== levelAt(reference, adresse);
}

/** The animateurs whose cells differ from the roster's — the ones a save sends. */
export function modifiedAnimateurs(
  cells: CellulesCompetences,
  reference: CellulesCompetences,
): string[] {
  const modified: string[] = [];
  for (const [animateurId, row] of cells) {
    const origin = reference.get(animateurId);
    if (!origin) {
      modified.push(animateurId);
      continue;
    }
    for (const [typologieId, niveau] of row) {
      if ((origin.get(typologieId) ?? null) !== niveau) {
        modified.push(animateurId);
        break;
      }
    }
  }
  return modified;
}

/**
 * The body of the save: every appreciation of every modified animateur, as
 * the map the server replaces the fiche's with — a cell at `null` is simply
 * not in it.
 */
export function saisie(
  cells: CellulesCompetences,
  animateurIds: readonly string[],
  changedById: ReadonlyMap<string, string | null> = new Map(),
): SaisieAnimateurCompetences[] {
  return animateurIds.map((animateurId) => {
    const competences: Record<string, NiveauCompetence> = {};
    for (const [typologieId, niveau] of cells.get(animateurId) ?? []) {
      if (niveau !== null) {
        competences[typologieId] = niveau;
      }
    }
    return { animateurId, modifieLe: changedById.get(animateurId) ?? null, competences };
  });
}

/**
 * What a key means in a cell: `0`, Backspace and Delete clear it, `1` to `3`
 * set the level in the order of {@link NIVEAUX}; anything else is not a
 * value, and `undefined` says so.
 */
export function levelForKey(key: string): NiveauCompetence | null | undefined {
  if (key === '0' || key === 'Backspace' || key === 'Delete') {
    return null;
  }
  const index = ['1', '2', '3'].indexOf(key);
  return index < 0 ? undefined : NIVEAUX[index];
}

/** A click cycles through the levels: empty, beginner, autonomous, referent, then empty again. */
export function nextLevel(current: NiveauCompetence | null): NiveauCompetence | null {
  if (current === null) {
    return NIVEAUX[0];
  }
  const index = NIVEAUX.indexOf(current);
  return index < 0 || index === NIVEAUX.length - 1 ? null : NIVEAUX[index + 1];
}

/**
 * Where an arrow, Enter, Home or End moves from `current`, or `null` when the
 * key means nothing here. Enter goes down, the way a spreadsheet does, so a
 * column is typed top to bottom without reaching for the mouse.
 */
export function moveFrom(
  key: string,
  current: CompetenceAddress,
  animateurIds: readonly string[],
  typologieIds: readonly string[],
): CompetenceAddress | null {
  const row = animateurIds.indexOf(current.animateurId);
  const column = typologieIds.indexOf(current.typologieId);
  if (row < 0 || column < 0) {
    return null;
  }
  let targetRow = row;
  let targetColumn = column;
  switch (key) {
    case 'ArrowDown':
    case 'Enter':
      targetRow = Math.min(row + 1, animateurIds.length - 1);
      break;
    case 'ArrowUp':
      targetRow = Math.max(row - 1, 0);
      break;
    case 'ArrowRight':
      targetColumn = Math.min(column + 1, typologieIds.length - 1);
      break;
    case 'ArrowLeft':
      targetColumn = Math.max(column - 1, 0);
      break;
    case 'Home':
      targetColumn = 0;
      break;
    case 'End':
      targetColumn = typologieIds.length - 1;
      break;
    default:
      return null;
  }
  return { animateurId: animateurIds[targetRow], typologieId: typologieIds[targetColumn] };
}

/** The rows the quick filter keeps: id, prénom and nom, every term matched somewhere. */
export function filterAnimateurs(animateurs: readonly Animateur[], query: string): Animateur[] {
  return animateurs.filter((animateur) =>
    correspondAuFiltre(query, [animateur.id, animateur.prenom, animateur.nom]),
  );
}

/**
 * The typologies chosen in `?typologies=a,b`, kept to the ones the referential
 * has: an obsolete link degrades to fewer columns, never to an error, and an
 * empty choice means every column.
 */
export function readTypologiesParam(param: string | null, known: readonly string[]): string[] {
  if (!param) {
    return [];
  }
  const wanted = new Set(param.split(',').map((id) => id.trim()));
  return known.filter((id) => wanted.has(id));
}

/**
 * The cells after a reload: the roster's, except for the rows in `kept`,
 * which keep what was typed — a row the server refused (stale, and the user
 * chose neither to reload nor to overwrite; or rejected) is still the user's.
 */
export function keepLocalRows(
  reference: CellulesCompetences,
  cells: CellulesCompetences,
  kept: ReadonlySet<string>,
): CellulesCompetences {
  if (kept.size === 0) {
    return reference;
  }
  const merged = new Map(reference);
  for (const animateurId of kept) {
    const local = cells.get(animateurId);
    if (local) {
      merged.set(animateurId, local);
    }
  }
  return merged;
}
