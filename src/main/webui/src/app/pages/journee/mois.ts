// The pure side of the Planning page's day selector: the event's days laid out
// as the months they fall in, each day with what the relecture needs to see
// before opening it — seats nobody holds, the reading recorded, a lock, a
// consigne. The month calendar that used to answer this on a screen of its own
// (`/calendar`) is this and nothing more.

import { buildMonthCells, parseDateKey, toDateKey, toMonthKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { PosteAffectation } from '../../core/models';
import { JourEvenement } from './journee';

/** What the selector knows of a day beyond its date, looked up by the page in the stores. */
export interface MarqueursJour {
  relu: (date: string) => boolean;
  verrou: (date: string) => boolean;
  consigne: (date: string) => boolean;
}

/** One cell of a month: a date, an event day or not, and what it carries. */
export interface CaseMois {
  /** `AAAA-MM-JJ`. */
  date: string;
  /** Day of the month, `5`. */
  numero: number;
  /** The event day the cell is, null for a date the plan holds no timeslot on. */
  jour: JourEvenement | null;
  /** Seats of the day nobody holds; 0 outside the event. */
  vides: number;
  relu: boolean;
  verrou: boolean;
  consigne: boolean;
  /** The server's today — never the browser's. */
  aujourdhui: boolean;
  /** Belongs to the neighbouring month: drawn blank. */
  horsMois: boolean;
}

export interface MoisAffiche {
  /** `AAAA-MM`. */
  cle: string;
  /** `septembre 2026`, in the UI's language. */
  titre: string;
  /** Weeks, Monday first; a week lying wholly outside the month is dropped. */
  semaines: CaseMois[][];
}

/** How many seats nobody holds, per date of the plan. */
export function emptySeatsByDate(postes: readonly PosteAffectation[]): Map<string, number> {
  const vides = new Map<string, number>();
  for (const poste of postes) {
    const date = poste.creneau?.date;
    if (date && poste.stand && !poste.animateur) {
      vides.set(date, (vides.get(date) ?? 0) + 1);
    }
  }
  return vides;
}

/**
 * The months the event's dated days fall in, in order, each as the weeks
 * that hold one of its days. A plan whose timeslots carry no date gives no
 * month: the selector then lists the days as they are numbered.
 */
export function eventMonths(
  jours: readonly JourEvenement[],
  vides: ReadonlyMap<string, number>,
  marqueurs: MarqueursJour,
  aujourdhui: string | null,
): MoisAffiche[] {
  const byDate = new Map<string, JourEvenement>();
  for (const jour of jours) {
    if (jour.date) {
      byDate.set(jour.date, jour);
    }
  }
  const keys = [...new Set([...byDate.keys()].map((date) => date.slice(0, 7)))].sort((a, b) =>
    a.localeCompare(b),
  );
  return keys.map((cle) => {
    const premier = parseDateKey(`${cle}-01`);
    const cases = buildMonthCells(premier).map((jourCalendrier) => {
      const date = toDateKey(jourCalendrier);
      const jour = byDate.get(date) ?? null;
      return {
        date,
        numero: jourCalendrier.getDate(),
        jour,
        vides: jour ? (vides.get(date) ?? 0) : 0,
        relu: jour !== null && marqueurs.relu(date),
        verrou: jour !== null && marqueurs.verrou(date),
        consigne: jour !== null && marqueurs.consigne(date),
        aujourdhui: date === aujourdhui,
        horsMois: toMonthKey(jourCalendrier) !== cle,
      };
    });
    const semaines: CaseMois[][] = [];
    for (let debut = 0; debut < cases.length; debut += 7) {
      const semaine = cases.slice(debut, debut + 7);
      if (semaine.some((jourCase) => !jourCase.horsMois)) {
        semaines.push(semaine);
      }
    }
    return {
      cle,
      titre: premier.toLocaleDateString(intlLocale(), { month: 'long', year: 'numeric' }),
      semaines,
    };
  });
}

/** The narrow weekday initials of a week, Monday first, in the UI's language. */
export function initialesSemaine(): string[] {
  // 2024-01-01 was a Monday.
  return Array.from({ length: 7 }, (_, index) =>
    new Date(2024, 0, 1 + index).toLocaleDateString(intlLocale(), { weekday: 'narrow' }),
  );
}

/** What a cell of the selector says aloud: the day, then each of its marks. */
export function libelleCase(cellule: CaseMois): string {
  const parties = [cellule.jour?.title ?? cellule.date];
  if (cellule.aujourdhui) {
    parties.push($localize`:@@journee.mois.aujourdhui:aujourd'hui`);
  }
  if (cellule.vides > 0) {
    parties.push($localize`:@@journee.mois.vides:${cellule.vides}:count: siège(s) vide(s)`);
  }
  if (cellule.relu) {
    parties.push($localize`:@@journee.mois.relu:relue et acceptée`);
  }
  if (cellule.verrou) {
    parties.push($localize`:@@journee.mois.verrou:verrouillée`);
  }
  if (cellule.consigne) {
    parties.push($localize`:@@journee.mois.consigne:sous consigne`);
  }
  return parties.join(' · ');
}
