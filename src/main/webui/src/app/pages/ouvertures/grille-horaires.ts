// The entry side of the "Ouvertures des stands" screen: one integer per stand
// and créneau, typed the way the organiser's own spreadsheet holds it. Pure
// functions, so the moves — a key, a paste, a day copied — are tested without
// rendering; the component only wires them to the DOM.
//
// The cells are held apart from the report they were read from: the report is
// what the server says, the cells are what the user is about to say. A stand
// whose cells differ from its report row is "modified", and only those stands
// are sent back — each with all its cells, since a save replaces the stand's
// whole schedule.

import { formatHeure } from '../../core/time-of-day';
import { CelluleCreneauOuverture, RapportOuvertures, SaisieStandGrille } from '../../core/models';

/** A cell's address: the stand, and the créneau column. */
export interface AdresseCellule {
  standId: string;
  creneauId: number;
}

/** One column of the grid: a créneau, under the day it belongs to. */
export interface ColonneGrille {
  date: string;
  creneauId: number;
  heureDebut: string;
  heureFin: string;
  /** Rank of the créneau within its day, for the header's own row. */
  rang: number;
}

/** `standId` → `creneauId` → headcount, `null` for closed. */
export type Cellules = Map<string, Map<number, number | null>>;

/** The columns in display order: day after day, each day's créneaux in the order the report gives. */
export function colonnes(rapport: RapportOuvertures): ColonneGrille[] {
  return rapport.jours.flatMap((jour) =>
    jour.creneaux.map((creneau, rang) => ({
      date: jour.date,
      creneauId: creneau.id,
      heureDebut: creneau.heureDebut,
      heureFin: creneau.heureFin,
      rang,
    })),
  );
}

/** The cells as the server reports them — the starting point, and the reference a change is measured against. */
export function cellulesDepuis(rapport: RapportOuvertures): Cellules {
  const cellules: Cellules = new Map();
  for (const ligne of rapport.stands) {
    const parCreneau = new Map<number, number | null>();
    for (const jour of ligne.jours) {
      for (const cellule of jour.creneaux) {
        parCreneau.set(cellule.creneauId, cellule.effectif);
      }
    }
    cellules.set(ligne.standId, parCreneau);
  }
  return cellules;
}

/**
 * The cells of a créneau belonging to another stagger family than the stand's,
 * keyed `standId#creneauId`. The stand never receives a seat there, so the
 * cell is neither typed nor sent — writing it would reopen a day this stand
 * never staffs.
 */
export function cellulesInertes(rapport: RapportOuvertures): Set<string> {
  const inertes = new Set<string>();
  for (const ligne of rapport.stands) {
    for (const jour of ligne.jours) {
      for (const cellule of jour.creneaux) {
        if (cellule.horsFamille) {
          inertes.add(key(ligne.standId, cellule.creneauId));
        }
      }
    }
  }
  return inertes;
}

/** The cells flagged partial by the server, keyed `standId#creneauId`: what a save would flatten. */
export function cellulesPartielles(rapport: RapportOuvertures): Set<string> {
  const partielles = new Set<string>();
  for (const ligne of rapport.stands) {
    for (const jour of ligne.jours) {
      for (const cellule of jour.creneaux) {
        if (cellule.partiel) {
          partielles.add(key(ligne.standId, cellule.creneauId));
        }
      }
    }
  }
  return partielles;
}

export function key(standId: string, creneauId: number): string {
  return `${standId}#${creneauId}`;
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
  ligne.set(adresse.creneauId, valeur);
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
    for (const [creneauId, valeur] of ligne) {
      if ((origine.get(creneauId) ?? null) !== valeur) {
        modifies.push(standId);
        break;
      }
    }
  }
  return modifies;
}

/**
 * The body of the save: every cell of every modified stand, the inert ones
 * left out — the server ignores them too, and sending them would say this
 * stand states something about another family's créneau.
 */
export function saisie(
  cellules: Cellules,
  standIds: readonly string[],
  inertes: ReadonlySet<string> = new Set(),
  modifieLeParStand: ReadonlyMap<string, string | null> = new Map(),
): SaisieStandGrille[] {
  return standIds.map((standId) => ({
    standId,
    modifieLe: modifieLeParStand.get(standId) ?? null,
    cellules: Array.from(cellules.get(standId) ?? [])
      .filter(([creneauId]) => !inertes.has(key(standId, creneauId)))
      .map(([creneauId, effectif]) => ({ creneauId, effectif })),
  }));
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
  const colonne = colonnesGrille.findIndex((each) => each.creneauId === courante.creneauId);
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
  return { standId: standIds[cibleLigne], creneauId: colonnesGrille[cibleColonne].creneauId };
}

/**
 * A block pasted from a spreadsheet, laid from `depuis` over the displayed
 * rows and columns: one line per stand, one tab-separated value per créneau.
 * Cells past the last row or column are dropped, a value that is not one
 * (`lireCellule`) leaves its cell alone.
 */
export function collerBloc(
  cellules: Cellules,
  text: string,
  depuis: AdresseCellule,
  standIds: readonly string[],
  colonnesGrille: readonly ColonneGrille[],
  inertes: ReadonlySet<string> = new Set(),
): Cellules {
  const ligne0 = standIds.indexOf(depuis.standId);
  const colonne0 = colonnesGrille.findIndex((each) => each.creneauId === depuis.creneauId);
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
      if (lu !== undefined && !inertes.has(key(standId, colonne.creneauId))) {
        resultat = ecrireCellule(resultat, { standId, creneauId: colonne.creneauId }, lu);
      }
    });
  });
  return resultat;
}

/**
 * Copies the cells of `dateSource` onto every other day, for `standIds`. A
 * target créneau takes the value of the source créneau with the same hours;
 * a créneau the source day does not have (a nocturne, say) is left as it is.
 */
export function recopierJour(
  cellules: Cellules,
  dateSource: string,
  standIds: readonly string[],
  colonnesGrille: readonly ColonneGrille[],
  inertes: ReadonlySet<string> = new Set(),
): Cellules {
  const source = colonnesGrille.filter((colonne) => colonne.date === dateSource);
  if (source.length === 0) {
    return cellules;
  }
  const parHeures = new Map(
    source.map((colonne) => [colonne.heureDebut + '-' + colonne.heureFin, colonne.creneauId]),
  );
  let resultat = cellules;
  for (const target of colonnesGrille) {
    if (target.date === dateSource) {
      continue;
    }
    const origine = parHeures.get(target.heureDebut + '-' + target.heureFin);
    if (origine === undefined) {
      continue;
    }
    for (const standId of standIds) {
      if (inertes.has(key(standId, target.creneauId)) || inertes.has(key(standId, origine))) {
        continue;
      }
      const valeur = resultat.get(standId)?.get(origine) ?? null;
      if ((resultat.get(standId)?.get(target.creneauId) ?? null) !== valeur) {
        resultat = ecrireCellule(resultat, { standId, creneauId: target.creneauId }, valeur);
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
        (ligne.get(colonne.creneauId) ?? null) !==
        (before.get(standId)?.get(colonne.creneauId) ?? null)
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
    if ((ligne?.get(colonne.creneauId) ?? null) !== null) {
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
  return colonnesGrille.map((colonne) => ligne?.get(colonne.creneauId) ?? null);
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
export function estPartielle(partielles: ReadonlySet<string>, adresse: AdresseCellule): boolean {
  return partielles.has(key(adresse.standId, adresse.creneauId));
}

export type { CelluleCreneauOuverture };
