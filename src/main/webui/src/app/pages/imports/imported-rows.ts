// Which rows of the referential an import report wrote, as the ids the list
// screens filter on (`?ids=`, `core/imported-rows.ts`). Pure: the card hands
// in the report and the rows the store reloaded after the write.
//
// A referential report names a row as the file did — its code, else its name;
// a timeslot by its date and its hours as typed — never by the id the
// database drew, so the ids are found again the way the server matched them.

import { normaliseHour, splitHourRange } from '../../core/horaire-stand';
import {
  Creneau,
  Emplacement,
  LigneImportReferentiel,
  ReferentielImportTarget,
  Stand,
  TypologieItem,
} from '../../core/models';

/** The rows of the store the resolution reads, and of each only what it compares. */
export interface ImportedRowsSource {
  typologies: readonly Pick<TypologieItem, 'id' | 'code' | 'label'>[];
  emplacements: readonly Pick<Emplacement, 'id' | 'code' | 'nom'>[];
  stands: readonly Pick<Stand, 'id' | 'code' | 'nom'>[];
  creneaux: readonly Pick<Creneau, 'id' | 'date' | 'heureDebut' | 'heureFin'>[];
}

/**
 * The name key the server compares names on (`Colonnes.key`): accents off,
 * lower case, nothing but letters and digits.
 */
function nameKey(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '');
}

/** A row named by its code first, by its name when no row carries that code. */
function byCodeOrName<T>(
  rows: readonly T[],
  key: string,
  code: (row: T) => string | null | undefined,
  name: (row: T) => string,
): T | undefined {
  return (
    rows.find((row) => code(row) === key) ?? rows.find((row) => nameKey(name(row)) === nameKey(key))
  );
}

/** `09:00:00`, the form a spreadsheet saves a time in, read as `09:00` like the server does. */
function hour(text: string): string | null {
  return normaliseHour(text.trim().replace(/^(\d{1,2}[:h.]\d{2})[:.]\d{2}$/i, '$1'));
}

function timeslotOf(
  creneaux: ImportedRowsSource['creneaux'],
  ligne: LigneImportReferentiel,
): ImportedRowsSource['creneaux'][number] | undefined {
  const plage = ligne.libelle ? splitHourRange(ligne.libelle) : null;
  const debut = plage ? hour(plage[0]) : null;
  const fin = plage ? hour(plage[1]) : null;
  if (debut === null || fin === null) {
    return undefined;
  }
  return creneaux.find(
    (creneau) =>
      creneau.date === ligne.id &&
      creneau.heureDebut.slice(0, 5) === debut &&
      creneau.heureFin.slice(0, 5) === fin,
  );
}

/**
 * The ids of the rows a written report created or updated, in the file's
 * order and each once; `null` for the day templates, which no list filters
 * (their card on the Créneaux page shows them all). A row the store no longer
 * holds is left out rather than guessed.
 */
export function importedRowIds(
  target: ReferentielImportTarget,
  lignes: readonly LigneImportReferentiel[],
  source: ImportedRowsSource,
): string[] | null {
  if (target === 'JOURNEES_TYPES') {
    return null;
  }
  const ids = new Set<string>();
  for (const ligne of lignes) {
    if (ligne.action === 'REFUSE' || ligne.id === null) {
      continue;
    }
    const key = ligne.id;
    let id: string | number | undefined;
    switch (target) {
      case 'TYPOLOGIES':
        id = byCodeOrName(
          source.typologies,
          key,
          (row) => row.code,
          (row) => row.label,
        )?.id;
        break;
      case 'EMPLACEMENTS':
        id = byCodeOrName(
          source.emplacements,
          key,
          (row) => row.code,
          (row) => row.nom,
        )?.id;
        break;
      case 'STANDS':
        id = byCodeOrName(
          source.stands,
          key,
          (row) => row.code,
          (row) => row.nom,
        )?.id;
        break;
      case 'CRENEAUX':
        id = timeslotOf(source.creneaux, ligne)?.id;
        break;
    }
    if (id !== undefined) {
      ids.add(String(id));
    }
  }
  return [...ids];
}
