// The grid of one stand on its fiche: its row of the Horaires des stands grid,
// laid out
// day by day — one line per day, one column per opening window of the
// edition's timeslots —, the way the organiser reads one stand. Pure, so the
// moves (a key, a paste, a day copied, the day above taken) are tested
// without rendering, and built on the very cells and save body of the
// Ouvertures grid (`../ouvertures/grille-horaires.ts`): what this grid saves
// is what that one would save for the same cells.

import { formatHeure } from '../../core/time-of-day';
import { JourAmplitude } from '../../core/models';
import {
  Cellules,
  ColonneGrille,
  ecrireCellule,
  libelleColonne,
  readCell,
} from '../ouvertures/grille-horaires';

/** One window of the edition's timeslots — the same hours on every day that has them. */
export interface StandGridWindow {
  /** `HH:mm-HH:mm`. */
  key: string;
  heureDebut: string;
  heureFin: string;
  /** `10-12`, as the Ouvertures grid names a column. */
  label: string;
}

/** One day of the stand: the column of each window on that day, or `null` where the day has none. */
export interface StandGridDay {
  date: string;
  jour: number;
  /** The public holiday, if any: tinted, never blocked. */
  ferie: string | null;
  /** The day template governing the date, if any. */
  template: string | null;
  cells: (ColonneGrille | null)[];
}

export interface StandGrid {
  windows: StandGridWindow[];
  days: StandGridDay[];
}

function windowKey(heureDebut: string, heureFin: string): string {
  return `${formatHeure(heureDebut)}-${formatHeure(heureFin)}`;
}

/**
 * The stand's grid: the distinct windows of the columns in time order, and
 * each day with its column under each window. Two columns of one day under the
 * same hours cannot exist — a column is a créneau cut at its bounds.
 */
export function standGrid(
  colonnes: readonly ColonneGrille[],
  jours: readonly JourAmplitude[],
  templates: ReadonlyMap<string, string> = new Map(),
): StandGrid {
  const windows = new Map<string, StandGridWindow>();
  for (const colonne of colonnes) {
    const key = windowKey(colonne.heureDebut, colonne.heureFin);
    if (!windows.has(key)) {
      windows.set(key, {
        key,
        heureDebut: formatHeure(colonne.heureDebut),
        heureFin: formatHeure(colonne.heureFin),
        label: libelleColonne(colonne),
      });
    }
  }
  const ordered = [...windows.values()].sort(
    (left, right) =>
      left.heureDebut.localeCompare(right.heureDebut) ||
      left.heureFin.localeCompare(right.heureFin),
  );
  const days = jours.map((jour) => {
    const byKey = new Map(
      colonnes
        .filter((colonne) => colonne.date === jour.date)
        .map((colonne) => [windowKey(colonne.heureDebut, colonne.heureFin), colonne]),
    );
    return {
      date: jour.date,
      jour: jour.jour,
      ferie: jour.ferie,
      template: templates.get(jour.date) ?? null,
      cells: ordered.map((window) => byKey.get(window.key) ?? null),
    };
  });
  return { windows: ordered, days };
}

/** The headcount of one cell of the stand; `null` for closed, and for a window the day does not have. */
export function cellValue(
  cellules: Cellules,
  standId: string,
  colonne: ColonneGrille | null,
): number | null {
  return colonne ? (cellules.get(standId)?.get(colonne.colonneId) ?? null) : null;
}

/**
 * A block pasted from a spreadsheet from one cell: one line per day, one
 * tab-separated value per window. A value that is not one (`readCell`) and a
 * window the day does not have leave their cell alone.
 */
export function pasteBlock(
  cellules: Cellules,
  standId: string,
  grid: StandGrid,
  text: string,
  fromDay: number,
  fromWindow: number,
): Cellules {
  let result = cellules;
  const lines = text.replaceAll('\r', '').split('\n');
  // A trailing newline, which every spreadsheet copy carries, is not a line.
  if (lines.length > 1 && lines.at(-1) === '') {
    lines.pop();
  }
  lines.forEach((line, i) => {
    const day = grid.days[fromDay + i];
    if (!day) {
      return;
    }
    line.split('\t').forEach((value, j) => {
      const colonne = day.cells[fromWindow + j];
      const read = readCell(value);
      if (colonne && read !== undefined) {
        result = ecrireCellule(result, { standId, colonneId: colonne.colonneId }, read);
      }
    });
  });
  return result;
}

/**
 * The day above taken into this one, window by window (Ctrl+D): what the
 * other grids call « reprendre la ligne du dessus ». A window this day does
 * not have is skipped; one the day above does not have closes nothing.
 */
export function copyDayAbove(
  cellules: Cellules,
  standId: string,
  grid: StandGrid,
  dayIndex: number,
): Cellules {
  const above = grid.days[dayIndex - 1];
  const day = grid.days[dayIndex];
  if (!above || !day) {
    return cellules;
  }
  let result = cellules;
  day.cells.forEach((colonne, index) => {
    const source = above.cells[index];
    if (colonne && source) {
      result = ecrireCellule(
        result,
        { standId, colonneId: colonne.colonneId },
        cellValue(cellules, standId, source),
      );
    }
  });
  return result;
}

/**
 * What a day of the stand amounts to, in one line: its open windows merged
 * where they touch at the same headcount — « 10-12 ×2, 14-20 ×4 » —, or
 * nothing when it is closed all day.
 */
export function dayPreview(
  cellules: Cellules,
  standId: string,
  grid: StandGrid,
  day: StandGridDay,
): { heureDebut: string; heureFin: string; effectif: number }[] {
  const stretches: { heureDebut: string; heureFin: string; effectif: number }[] = [];
  day.cells.forEach((colonne, index) => {
    const effectif = cellValue(cellules, standId, colonne);
    if (colonne === null || effectif === null) {
      return;
    }
    const window = grid.windows[index];
    const last = stretches.at(-1);
    if (last && last.heureFin === window.heureDebut && last.effectif === effectif) {
      last.heureFin = window.heureFin;
    } else {
      stretches.push({ heureDebut: window.heureDebut, heureFin: window.heureFin, effectif });
    }
  });
  return stretches;
}
