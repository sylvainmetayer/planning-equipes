// The entry side of the "Ouvertures des stands" screen: one integer per stand
// and column, typed the way the organiser's own spreadsheet holds it. Pure
// functions, so the moves — a key, a paste, a day copied — are tested without
// rendering; the component only wires them to the DOM.
//
// A column is a créneau, or a tranche of it: the server cuts a créneau
// wherever a stand's windows draw a boundary inside it (4 people from 14:00
// to 19:00 then 2 until 20:00 gives 14-19 and 19-20), so the grid has the
// workbook's columns while the créneaux stay the solver's. A column is
// identified by its créneau and its bounds, never by a rank: a boundary the
// user adds in the screen (« scinder ») makes a column that exists nowhere
// else until a cell under it is saved.
//
// The cells are held apart from the report they were read from: the report is
// what the server says, the cells are what the user is about to say. A stand
// whose cells differ from its report row is "modified", and only those stands
// are sent back — each with all its cells, since a save replaces the stand's
// whole schedule.

import { formatHeure } from '../../core/time-of-day';
import {
  CelluleCreneauOuverture,
  RapportOuvertures,
  SaisieStandGrille,
  SegmentCellule,
} from '../../core/models';

/** A cell's address: the stand, and the column. */
export interface AdresseCellule {
  standId: string;
  colonneId: string;
}

/** One column of the grid: a tranche of a créneau, under the day it belongs to. */
export interface ColonneGrille {
  date: string;
  creneauId: number;
  /** `creneauId@HH:mm-HH:mm`: the créneau and the column's bounds inside it. */
  colonneId: string;
  heureDebut: string;
  heureFin: string;
  /** Rank of the column within its day, for the header's own row. */
  rang: number;
}

/** `standId` → `colonneId` → headcount, `null` for closed. */
export type Cellules = Map<string, Map<string, number | null>>;

/** The column's identity: its créneau and its bounds, hours in `HH:mm`. */
export function colonneId(creneauId: number, heureDebut: string, heureFin: string): string {
  return `${creneauId}@${formatHeure(heureDebut)}-${formatHeure(heureFin)}`;
}

/** The columns in display order: day after day, each day's columns in the order the report gives. */
export function colonnes(rapport: RapportOuvertures): ColonneGrille[] {
  return rapport.jours.flatMap((jour) =>
    jour.creneaux.map((creneau, rang) => ({
      date: jour.date,
      creneauId: creneau.id,
      colonneId: colonneId(creneau.id, creneau.heureDebut, creneau.heureFin),
      heureDebut: creneau.heureDebut,
      heureFin: creneau.heureFin,
      rang,
    })),
  );
}

/**
 * The column id of every cell of the report, by day, créneau and tranche: a
 * cell names its créneau and tranche, the day's column list says which
 * bounds those are.
 */
function colonneByCell(rapport: RapportOuvertures): Map<string, string> {
  const ids = new Map<string, string>();
  for (const jour of rapport.jours) {
    for (const [rang, creneau] of jour.creneaux.entries()) {
      ids.set(
        `${jour.date}#${creneau.id}#${creneau.tranche ?? rang}`,
        colonneId(creneau.id, creneau.heureDebut, creneau.heureFin),
      );
    }
  }
  return ids;
}

function idOf(
  ids: ReadonlyMap<string, string>,
  date: string,
  cellule: CelluleCreneauOuverture,
): string | undefined {
  return ids.get(`${date}#${cellule.creneauId}#${cellule.tranche ?? 0}`);
}

/** The cells as the server reports them — the starting point, and the reference a change is measured against. */
export function cellulesDepuis(rapport: RapportOuvertures): Cellules {
  const ids = colonneByCell(rapport);
  const cellules: Cellules = new Map();
  for (const ligne of rapport.stands) {
    const byColonne = new Map<string, number | null>();
    for (const jour of ligne.jours) {
      for (const cellule of jour.creneaux) {
        const id = idOf(ids, jour.date, cellule);
        if (id !== undefined) {
          byColonne.set(id, cellule.effectif);
        }
      }
    }
    cellules.set(ligne.standId, byColonne);
  }
  return cellules;
}

/** The cells flagged partial by the server, keyed `standId#colonneId`: what a save keeps unless retyped. */
export function cellulesPartielles(rapport: RapportOuvertures): Set<string> {
  return cellulesTelles(rapport, (cellule) => cellule.partiel);
}

function cellulesTelles(
  rapport: RapportOuvertures,
  telle: (cellule: CelluleCreneauOuverture) => boolean,
): Set<string> {
  const ids = colonneByCell(rapport);
  const clefs = new Set<string>();
  for (const ligne of rapport.stands) {
    for (const jour of ligne.jours) {
      for (const cellule of jour.creneaux) {
        const id = idOf(ids, jour.date, cellule);
        if (id !== undefined && telle(cellule)) {
          clefs.add(key(ligne.standId, id));
        }
      }
    }
  }
  return clefs;
}

/** The stretches behind every partial cell, keyed `standId#colonneId`: what a save keeps, and what « Aligner » would extend. */
export function segmentsPartiels(rapport: RapportOuvertures): Map<string, SegmentCellule[]> {
  const ids = colonneByCell(rapport);
  const segments = new Map<string, SegmentCellule[]>();
  for (const ligne of rapport.stands) {
    for (const jour of ligne.jours) {
      for (const cellule of jour.creneaux) {
        const id = idOf(ids, jour.date, cellule);
        if (id !== undefined && cellule.partiel) {
          segments.set(key(ligne.standId, id), cellule.segments);
        }
      }
    }
  }
  return segments;
}

/** What flattening every partial cell onto its column would add: the stands, the cells, and the minutes of opening. */
export interface Aplatissement {
  stands: string[];
  cases: number;
  minutes: number;
}

/**
 * The cost of « Aligner » before it is paid: each partial cell extended to
 * its whole column at its highest headcount, minus what its stretches already
 * cover. The number the confirmation shows, so nobody aligns 52 cells to find
 * out afterwards that the week grew by 74 hours.
 */
export function aplatissement(
  segments: ReadonlyMap<string, SegmentCellule[]>,
  colonnesGrille: readonly ColonneGrille[],
): Aplatissement {
  const durationByColonne = new Map(
    colonnesGrille.map((colonne) => [
      colonne.colonneId,
      minutesBetween(colonne.heureDebut, colonne.heureFin),
    ]),
  );
  const stands = new Set<string>();
  let cases = 0;
  let minutes = 0;
  for (const [clef, stretches] of segments) {
    const [standId, id] = clef.split('#');
    const duree = durationByColonne.get(id);
    if (duree === undefined || stretches.length === 0) {
      continue;
    }
    stands.add(standId);
    cases++;
    const peak = Math.max(...stretches.map((segment) => segment.effectif));
    const couvert = stretches.reduce(
      (total, segment) =>
        total + segment.effectif * minutesBetween(segment.heureDebut, segment.heureFin),
      0,
    );
    minutes += peak * duree - couvert;
  }
  return { stands: Array.from(stands), cases, minutes };
}

/** Minutes from one wall-clock hour to the next, a `00:00` end counting as midnight. */
export function minutesBetween(heureDebut: string, heureFin: string): number {
  const debut = minutesOfDay(heureDebut);
  const fin = minutesOfDay(heureFin);
  return fin > debut ? fin - debut : fin + 24 * 60 - debut;
}

function minutesOfDay(heure: string): number {
  return Number(heure.slice(0, 2)) * 60 + Number(heure.slice(3, 5));
}

export function key(standId: string, colonneId: string): string {
  return `${standId}#${colonneId}`;
}

/**
 * What a typed value means: digits are a headcount, and an empty field, a
 * dash or a zero all mean "closed" — the three ways a spreadsheet writes an
 * empty cell. Anything else is not a value, and `undefined` says so.
 */
export function readCell(text: string): number | null | undefined {
  const propre = text.trim();
  if (propre === '' || propre === '-' || propre === '—' || propre === '0') {
    return null;
  }
  if (!/^\d+$/.test(propre)) {
    return undefined;
  }
  return Number(propre);
}

/** A copy of `cellules` with one cell changed; the map is never mutated, so a signal holding it notifies. */
export function ecrireCellule(
  cellules: Cellules,
  adresse: AdresseCellule,
  valeur: number | null,
): Cellules {
  const copie = new Map(cellules);
  const ligne = new Map(copie.get(adresse.standId) ?? []);
  ligne.set(adresse.colonneId, valeur);
  copie.set(adresse.standId, ligne);
  return copie;
}

/** The stands whose cells differ from the report's — the ones a save sends. */
export function standsModifies(cellules: Cellules, reference: Cellules): string[] {
  const modifies: string[] = [];
  for (const [standId, ligne] of cellules) {
    const origine = reference.get(standId);
    if (!origine) {
      modifies.push(standId);
      continue;
    }
    for (const [id, valeur] of ligne) {
      if ((origine.get(id) ?? null) !== valeur) {
        modifies.push(standId);
        break;
      }
    }
  }
  return modifies;
}

/** What travels with a save besides the cells. */
export interface OptionsSaisie {
  /** The stamps the grid read, sent back as preconditions (issue #362). */
  modifieLeParStand?: ReadonlyMap<string, string | null>;
  /** The explicit request to extend partial cells to their column; without it a cell saved unchanged keeps its stretches. */
  aplatir?: boolean;
}

/**
 * The body of the save: every cell of every modified stand, each with its
 * column's bounds so a column the screen cut itself writes a window at
 * those bounds.
 */
export function saisie(
  cellules: Cellules,
  standIds: readonly string[],
  colonnesGrille: readonly ColonneGrille[],
  options: OptionsSaisie = {},
): SaisieStandGrille[] {
  return standIds.map((standId) => {
    const ligne = cellules.get(standId) ?? new Map<string, number | null>();
    return {
      standId,
      modifieLe: options.modifieLeParStand?.get(standId) ?? null,
      cellules: colonnesGrille
        .filter((colonne) => ligne.has(colonne.colonneId))
        .map((colonne) => ({
          creneauId: colonne.creneauId,
          heureDebut: formatHeure(colonne.heureDebut),
          heureFin: formatHeure(colonne.heureFin),
          effectif: ligne.get(colonne.colonneId) ?? null,
        })),
      aplatir: options.aplatir ?? false,
    };
  });
}

/**
 * The columns with one of them cut at `heure`: two columns where there was
 * one, the hour strictly inside the column's bounds — or `null` when it is
 * not, a cut on an edge being no cut. The new columns exist in the screen
 * only; a cell saved under one of them writes a window at its bounds, and
 * the server then reports the boundary like any other.
 */
export function scinder(
  colonnesGrille: readonly ColonneGrille[],
  id: string,
  heure: string,
): ColonneGrille[] | null {
  const index = colonnesGrille.findIndex((colonne) => colonne.colonneId === id);
  if (index < 0 || !/^\d{2}:\d{2}$/.test(heure)) {
    return null;
  }
  const colonne = colonnesGrille[index];
  const debut = minutesOfDay(colonne.heureDebut);
  const coupe = minutesOfDay(heure);
  const fin = debut + minutesBetween(colonne.heureDebut, colonne.heureFin);
  const coupeAbsolue = coupe < debut ? coupe + 24 * 60 : coupe;
  if (coupeAbsolue <= debut || coupeAbsolue >= fin) {
    return null;
  }
  const before: ColonneGrille = {
    ...colonne,
    colonneId: colonneId(colonne.creneauId, colonne.heureDebut, heure),
    heureFin: heure,
  };
  const after: ColonneGrille = {
    ...colonne,
    colonneId: colonneId(colonne.creneauId, heure, colonne.heureFin),
    heureDebut: heure,
    rang: colonne.rang + 1,
  };
  return [
    ...colonnesGrille.slice(0, index),
    before,
    after,
    ...colonnesGrille
      .slice(index + 1)
      .map((suivante) =>
        suivante.date === colonne.date ? { ...suivante, rang: suivante.rang + 1 } : suivante,
      ),
  ];
}

/** The cells after a cut: every stand's value under the old column carried onto both new ones. */
export function propagerScission(
  cellules: Cellules,
  ancienne: string,
  nouvelles: readonly string[],
): Cellules {
  const copie: Cellules = new Map();
  for (const [standId, ligne] of cellules) {
    if (!ligne.has(ancienne)) {
      copie.set(standId, ligne);
      continue;
    }
    const valeur = ligne.get(ancienne) ?? null;
    const nouvelle = new Map(ligne);
    nouvelle.delete(ancienne);
    for (const id of nouvelles) {
      nouvelle.set(id, valeur);
    }
    copie.set(standId, nouvelle);
  }
  return copie;
}

/** The keys of a map renamed after a cut: what was known of the old column holds on both new ones. */
export function propagerClefs<T>(
  source: ReadonlyMap<string, T>,
  ancienne: string,
  nouvelles: readonly string[],
): Map<string, T> {
  const copie = new Map<string, T>();
  for (const [clef, valeur] of source) {
    const [standId, id] = clef.split('#');
    if (id !== ancienne) {
      copie.set(clef, valeur);
      continue;
    }
    for (const nouvelle of nouvelles) {
      copie.set(key(standId, nouvelle), valeur);
    }
  }
  return copie;
}

/**
 * Where an arrow, Enter or Tab-like key moves from `courante`, or `null` when
 * the key means nothing here. Enter goes down, the way a spreadsheet does,
 * so a column is typed top to bottom without reaching for the mouse.
 */
export function deplacement(
  key: string,
  courante: AdresseCellule,
  standIds: readonly string[],
  colonnesGrille: readonly ColonneGrille[],
): AdresseCellule | null {
  const ligne = standIds.indexOf(courante.standId);
  const colonne = colonnesGrille.findIndex((each) => each.colonneId === courante.colonneId);
  if (ligne < 0 || colonne < 0) {
    return null;
  }
  let cibleLigne = ligne;
  let cibleColonne = colonne;
  switch (key) {
    case 'ArrowDown':
    case 'Enter':
      cibleLigne = Math.min(ligne + 1, standIds.length - 1);
      break;
    case 'ArrowUp':
      cibleLigne = Math.max(ligne - 1, 0);
      break;
    case 'ArrowRight':
      cibleColonne = Math.min(colonne + 1, colonnesGrille.length - 1);
      break;
    case 'ArrowLeft':
      cibleColonne = Math.max(colonne - 1, 0);
      break;
    case 'Home':
      cibleColonne = 0;
      break;
    case 'End':
      cibleColonne = colonnesGrille.length - 1;
      break;
    default:
      return null;
  }
  return { standId: standIds[cibleLigne], colonneId: colonnesGrille[cibleColonne].colonneId };
}

/**
 * A block pasted from a spreadsheet, laid from `depuis` over the displayed
 * rows and columns: one line per stand, one tab-separated value per column.
 * Cells past the last row or column are dropped, a value that is not one
 * (`readCell`) leaves its cell alone.
 */
export function collerBloc(
  cellules: Cellules,
  text: string,
  depuis: AdresseCellule,
  standIds: readonly string[],
  colonnesGrille: readonly ColonneGrille[],
): Cellules {
  const ligne0 = standIds.indexOf(depuis.standId);
  const colonne0 = colonnesGrille.findIndex((each) => each.colonneId === depuis.colonneId);
  if (ligne0 < 0 || colonne0 < 0) {
    return cellules;
  }
  let resultat = cellules;
  const lignes = text.replace(/\r/g, '').split('\n');
  // A trailing newline, which every spreadsheet copy carries, is not a row.
  if (lignes.length > 1 && lignes[lignes.length - 1] === '') {
    lignes.pop();
  }
  lignes.forEach((ligneTexte, i) => {
    const standId = standIds[ligne0 + i];
    if (standId === undefined) {
      return;
    }
    ligneTexte.split('\t').forEach((valeur, j) => {
      const colonne = colonnesGrille[colonne0 + j];
      if (colonne === undefined) {
        return;
      }
      const lu = readCell(valeur);
      if (lu !== undefined) {
        resultat = ecrireCellule(resultat, { standId, colonneId: colonne.colonneId }, lu);
      }
    });
  });
  return resultat;
}

/**
 * Copies the cells of `dateSource` onto every other day, for `standIds`. A
 * target column takes the value of the source column with the same hours;
 * a column the source day does not have (a nocturne, say) is left as it is.
 */
export function recopierJour(
  cellules: Cellules,
  dateSource: string,
  standIds: readonly string[],
  colonnesGrille: readonly ColonneGrille[],
): Cellules {
  const source = colonnesGrille.filter((colonne) => colonne.date === dateSource);
  if (source.length === 0) {
    return cellules;
  }
  const heures = (colonne: ColonneGrille) =>
    formatHeure(colonne.heureDebut) + '-' + formatHeure(colonne.heureFin);
  const byHours = new Map(source.map((colonne) => [heures(colonne), colonne.colonneId]));
  let resultat = cellules;
  for (const target of colonnesGrille) {
    if (target.date === dateSource) {
      continue;
    }
    const origine = byHours.get(heures(target));
    if (origine === undefined) {
      continue;
    }
    for (const standId of standIds) {
      const valeur = resultat.get(standId)?.get(origine) ?? null;
      if ((resultat.get(standId)?.get(target.colonneId) ?? null) !== valeur) {
        resultat = ecrireCellule(resultat, { standId, colonneId: target.colonneId }, valeur);
      }
    }
  }
  return resultat;
}

/** How many cells a copy of one day onto the others actually changed. */
export function countCopied(
  before: Cellules,
  after: Cellules,
  colonnesGrille: readonly ColonneGrille[],
): number {
  let changees = 0;
  for (const [standId, ligne] of after) {
    for (const colonne of colonnesGrille) {
      if (
        (ligne.get(colonne.colonneId) ?? null) !==
        (before.get(standId)?.get(colonne.colonneId) ?? null)
      ) {
        changees++;
      }
    }
  }
  return changees;
}

/** The first day on which a stand has any headcount typed, or the first day — the row copy's default source. */
export function jourDeReference(
  cellules: Cellules,
  standId: string,
  colonnesGrille: readonly ColonneGrille[],
): string | null {
  const ligne = cellules.get(standId);
  for (const colonne of colonnesGrille) {
    if ((ligne?.get(colonne.colonneId) ?? null) !== null) {
      return colonne.date;
    }
  }
  return colonnesGrille[0]?.date ?? null;
}

/** The headcounts of one row, in column order — what the row's own summary reads. */
export function valeursLigne(
  cellules: Cellules,
  standId: string,
  colonnesGrille: readonly ColonneGrille[],
): (number | null)[] {
  const ligne = cellules.get(standId);
  return colonnesGrille.map((colonne) => ligne?.get(colonne.colonneId) ?? null);
}

/**
 * `10:00`-`12:00` → `10-12`; `20:00`-`00:00` → `20-00`; a half hour keeps its
 * minutes: `13:30-14`.
 *
 * The API sends a time as `HH:mm:ss`, so the seconds go first: testing the
 * whole wire form for a trailing `:00` matched *every* hour and turned
 * `13:30-14:30` into `13-14`, giving two neighbouring columns the same label.
 */
export function libelleColonne(colonne: ColonneGrille): string {
  const court = (heure: string) => {
    const hhmm = formatHeure(heure);
    return hhmm.endsWith(':00') ? hhmm.slice(0, 2) : hhmm;
  };
  return `${court(colonne.heureDebut)}-${court(colonne.heureFin)}`;
}

/** Whether the report knows this cell as partial. */
export function isPartialCell(partielles: ReadonlySet<string>, adresse: AdresseCellule): boolean {
  return partielles.has(key(adresse.standId, adresse.colonneId));
}

export type { CelluleCreneauOuverture };
