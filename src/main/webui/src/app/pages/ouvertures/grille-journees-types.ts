// The entry grid, read by kind of day instead of by date.
//
// The créneaux of an edition repeat: twelve days of « 10-12 / 12-13 / 13-14 /
// 14-20 », four of « 9-12 / 13-16 ». The day templates already name those
// shapes and say which dates they govern (ADR 0032), so a stand's schedule is
// almost always one row per template — "on a Journée I run 14:00-20:00 with
// two people" — and the sixty-two columns of the dated grid are the same
// seventeen said over and over.
//
// This is a projection, not a second store. A column stands for one vacation
// of one template and writes to every dated column it covers; reading it back
// asks whether those dated columns agree, and says « écart » when they do not.
// Nothing new is persisted and nothing new is sent: the save is the dated
// grid's own, with every date the projection touched.

import { formatHeure } from '../../core/time-of-day';
import { AffectationJourneeType, EtatJourneesTypes, RapportOuvertures } from '../../core/models';
import { Cellules, ColonneGrille, colonnes, ecrireCellule } from './grille-horaires';

/** One column: a vacation of a template, and the dated columns it stands for. */
export interface ColonneJourneeType {
  journeeTypeId: number;
  nomJourneeType: string;
  /** `jt:<id>@HH:mm-HH:mm` — the template and the vacation's bounds. */
  colonneId: string;
  heureDebut: string;
  heureFin: string;
  /** Rank of the vacation inside its template, for the header's own row. */
  rang: number;
  /** A meal-relay vacation: the seats generated are half the headcount typed, rounded up. */
  couverturePause: boolean;
  /** The dated grid columns this one writes to — one per date the template governs. */
  colonnes: readonly string[];
  /** Dates the template governs whose grid holds no such column: they cannot be typed here. */
  datesSansColonne: readonly string[];
}

/** What one stand does on one template column: a headcount, closed, or dates that disagree. */
export type ValeurJourneeType = number | null | 'ecart';

/** The dates of a template, in order, from the calendar. */
function datesParJourneeType(calendrier: readonly AffectationJourneeType[]): Map<number, string[]> {
  const dates = new Map<number, string[]>();
  for (const affectation of [...calendrier].sort((a, b) => a.date.localeCompare(b.date))) {
    const connues = dates.get(affectation.journeeTypeId) ?? [];
    connues.push(affectation.date);
    dates.set(affectation.journeeTypeId, connues);
  }
  return dates;
}

/** The dated columns by `date|HH:mm-HH:mm`, so a vacation finds its own on each date. */
function colonnesParHeures(grille: readonly ColonneGrille[]): Map<string, string[]> {
  const index = new Map<string, string[]>();
  for (const colonne of grille) {
    const clef = `${colonne.date}|${formatHeure(colonne.heureDebut)}-${formatHeure(colonne.heureFin)}`;
    const connues = index.get(clef) ?? [];
    connues.push(colonne.colonneId);
    index.set(clef, connues);
  }
  return index;
}

/**
 * The columns of the per-template grid, template after template in the order
 * their first date falls. A template nothing governs has no column: it says
 * nothing about this edition's days.
 *
 * <p>A vacation finds its dated column by its own hours. A date whose créneau
 * was cut in two — a stand opening mid-slot — has no column spanning the whole
 * vacation, so it lands in `datesSansColonne` and keeps its dated value: the
 * per-template view never flattens a shape it cannot show.</p>
 */
export function colonnesJourneesTypes(
  rapport: RapportOuvertures,
  etat: EtatJourneesTypes | null,
): ColonneJourneeType[] {
  if (!etat) {
    return [];
  }
  const grille = colonnes(rapport);
  const parHeures = colonnesParHeures(grille);
  const dates = datesParJourneeType(etat.calendrier);
  const ordre = [...etat.journeesTypes]
    .filter((journeeType) => (dates.get(journeeType.id ?? -1) ?? []).length > 0)
    .sort((a, b) =>
      (dates.get(a.id ?? -1)?.[0] ?? '').localeCompare(dates.get(b.id ?? -1)?.[0] ?? ''),
    );
  const resultat: ColonneJourneeType[] = [];
  for (const journeeType of ordre) {
    const id = journeeType.id ?? -1;
    const gouvernees = dates.get(id) ?? [];
    for (const [rang, vacation] of journeeType.vacations.entries()) {
      const debut = formatHeure(vacation.heureDebut);
      const fin = formatHeure(vacation.heureFin);
      const couvertes: string[] = [];
      const manquantes: string[] = [];
      for (const date of gouvernees) {
        const candidates = parHeures.get(`${date}|${debut}-${fin}`) ?? [];
        if (candidates.length === 1) {
          couvertes.push(candidates[0]);
        } else {
          manquantes.push(date);
        }
      }
      resultat.push({
        journeeTypeId: id,
        nomJourneeType: journeeType.nom,
        colonneId: `jt:${id}@${debut}-${fin}`,
        heureDebut: vacation.heureDebut,
        heureFin: vacation.heureFin,
        rang,
        couverturePause: vacation.couverturePause,
        colonnes: couvertes,
        datesSansColonne: manquantes,
      });
    }
  }
  return resultat;
}

/**
 * What the stand does on that column: the value every date agrees on, or
 * `'ecart'` when they do not. A column no date covers reads as closed, and
 * typing into it writes nowhere — which is why the template view says so
 * rather than pretending the cell is empty.
 */
export function valeurJourneeType(
  cellules: Cellules,
  standId: string,
  colonne: ColonneJourneeType,
): ValeurJourneeType {
  const ligne = cellules.get(standId);
  let commune: number | null = null;
  let premier = true;
  for (const id of colonne.colonnes) {
    const valeur = ligne?.get(id) ?? null;
    if (premier) {
      commune = valeur;
      premier = false;
    } else if (valeur !== commune) {
      return 'ecart';
    }
  }
  return commune;
}

/** A copy of `cellules` with the value written onto every date the column stands for. */
export function ecrireColonneJourneeType(
  cellules: Cellules,
  standId: string,
  colonne: ColonneJourneeType,
  valeur: number | null,
): Cellules {
  let resultat = cellules;
  for (const id of colonne.colonnes) {
    if ((resultat.get(standId)?.get(id) ?? null) !== valeur) {
      resultat = ecrireCellule(resultat, { standId, colonneId: id }, valeur);
    }
  }
  return resultat;
}

/** How many dated cells one column stands for, and how many of them a stand states. */
export interface ResumeJourneesTypes {
  /** Columns the per-template grid shows. */
  colonnes: number;
  /** Dated cells those columns stand for — what the grid by date would ask for instead. */
  cellulesDatees: number;
  /** Cells where the dates disagree, and which therefore still need the grid by date. */
  ecarts: number;
  /** Dates a vacation could not be matched on, for the same reason. */
  datesSansColonne: number;
}

/**
 * What the per-template grid saves, and what it cannot say: the number the
 * screen shows so the choice between the two grids is made on facts.
 */
export function resumeJourneesTypes(
  cellules: Cellules,
  standIds: readonly string[],
  colonnesJT: readonly ColonneJourneeType[],
): ResumeJourneesTypes {
  let cellulesDatees = 0;
  let ecarts = 0;
  const datesSansColonne = new Set<string>();
  for (const colonne of colonnesJT) {
    cellulesDatees += colonne.colonnes.length * standIds.length;
    for (const date of colonne.datesSansColonne) {
      datesSansColonne.add(date);
    }
    for (const standId of standIds) {
      if (valeurJourneeType(cellules, standId, colonne) === 'ecart') {
        ecarts++;
      }
    }
  }
  return {
    colonnes: colonnesJT.length * standIds.length,
    cellulesDatees,
    ecarts,
    datesSansColonne: datesSansColonne.size,
  };
}

/** `10:00-12:00`, plus the relay marker — the header of one template column. */
export function libelleColonneJourneeType(colonne: ColonneJourneeType): string {
  return `${formatHeure(colonne.heureDebut)}-${formatHeure(colonne.heureFin)}`;
}
