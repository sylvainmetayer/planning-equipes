// The stand's grid on its fiche, day by day: built on the Ouvertures grid's
// columns and cells, so the moves are tested here and the save is that grid's.

import { describe, expect, it } from 'vitest';
import { JourAmplitude } from '../../core/models';
import { Cellules, ColonneGrille, colonneId } from '../ouvertures/grille-horaires';
import { cellValue, copyDayAbove, dayPreview, pasteBlock, standGrid } from './stand-grid';

function colonne(
  date: string,
  creneauId: number,
  heureDebut: string,
  heureFin: string,
): ColonneGrille {
  return {
    date,
    creneauId,
    colonneId: colonneId(creneauId, heureDebut, heureFin),
    heureDebut,
    heureFin,
    rang: 0,
    couverturePause: false,
  };
}

function jour(date: string, index: number): JourAmplitude {
  return {
    date,
    jour: index,
    heureDebut: '10:00',
    heureFin: '20:00',
    minutes: 600,
    nombreCreneaux: 2,
    creneaux: [],
    ferie: null,
  };
}

const COLONNES = [
  colonne('2026-07-11', 1, '10:00:00', '12:00:00'),
  colonne('2026-07-11', 2, '14:00:00', '20:00:00'),
  colonne('2026-07-12', 3, '10:00:00', '12:00:00'),
  colonne('2026-07-12', 4, '14:00:00', '20:00:00'),
  // A nocturne on the second day only.
  colonne('2026-07-12', 5, '20:00:00', '23:00:00'),
];
const JOURS = [jour('2026-07-11', 1), jour('2026-07-12', 2)];

function cells(values: Record<string, number | null>): Cellules {
  return new Map([['S1', new Map(Object.entries(values))]]);
}

describe('standGrid', () => {
  it('lays one line per day and one column per window, a missing window left empty', () => {
    const grid = standGrid(COLONNES, JOURS, new Map([['2026-07-12', 'Samedi']]));

    expect(grid.windows.map((window) => window.label)).toEqual(['10-12', '14-20', '20-23']);
    expect(grid.days.map((day) => day.template)).toEqual([null, 'Samedi']);
    expect(grid.days[0].cells.map((cell) => cell?.creneauId ?? null)).toEqual([1, 2, null]);
    expect(grid.days[1].cells.map((cell) => cell?.creneauId ?? null)).toEqual([3, 4, 5]);
  });
});

describe('the moves of the stand grid', () => {
  const grid = standGrid(COLONNES, JOURS);

  it('pastes a block from a spreadsheet from the cell it lands on, skipping what is no value', () => {
    const pasted = pasteBlock(cells({}), 'S1', grid, '2\t4\n3\tx\t1\n', 0, 0);

    expect(cellValue(pasted, 'S1', grid.days[0].cells[0])).toBe(2);
    expect(cellValue(pasted, 'S1', grid.days[0].cells[1])).toBe(4);
    expect(cellValue(pasted, 'S1', grid.days[1].cells[0])).toBe(3);
    // « x » is no value: the cell is left as it was.
    expect(pasted.get('S1')!.has(grid.days[1].cells[1]!.colonneId)).toBe(false);
    expect(cellValue(pasted, 'S1', grid.days[1].cells[2])).toBe(1);
  });

  it('takes the day above window by window (Ctrl+D)', () => {
    const start = cells({
      [grid.days[0].cells[0]!.colonneId]: 2,
      [grid.days[0].cells[1]!.colonneId]: null,
      [grid.days[1].cells[2]!.colonneId]: 5,
    });
    const copied = copyDayAbove(start, 'S1', grid, 1);

    expect(cellValue(copied, 'S1', grid.days[1].cells[0])).toBe(2);
    expect(cellValue(copied, 'S1', grid.days[1].cells[1])).toBeNull();
    // A window the day above does not have closes nothing.
    expect(cellValue(copied, 'S1', grid.days[1].cells[2])).toBe(5);
    expect(copyDayAbove(start, 'S1', grid, 0)).toBe(start);
  });

  it('sums a day up, touching windows at one headcount merged', () => {
    const touching = standGrid(
      [
        colonne('2026-07-11', 1, '10:00:00', '12:00:00'),
        colonne('2026-07-11', 2, '12:00:00', '14:00:00'),
        colonne('2026-07-11', 3, '14:00:00', '20:00:00'),
      ],
      [JOURS[0]],
    );
    const day = touching.days[0];
    const values = cells({
      [day.cells[0]!.colonneId]: 2,
      [day.cells[1]!.colonneId]: 2,
      [day.cells[2]!.colonneId]: 4,
    });

    expect(dayPreview(values, 'S1', touching, day)).toEqual([
      { heureDebut: '10:00', heureFin: '14:00', effectif: 2 },
      { heureDebut: '14:00', heureFin: '20:00', effectif: 4 },
    ]);
    expect(dayPreview(cells({}), 'S1', touching, day)).toEqual([]);
  });
});
