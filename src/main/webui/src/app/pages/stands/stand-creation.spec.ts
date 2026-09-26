// The guided creation's last step, turned into the stand's cells: no rule and
// no syntax typed, the case of the ticket — open 10–12 at 2 and 14–20 at 4,
// closed on Mondays — written by ticking and typing headcounts only.

import { describe, expect, it } from 'vitest';
import { JourAmplitude } from '../../core/models';
import { ColonneGrille, colonneId } from '../ouvertures/grille-horaires';
import {
  creationBounds,
  creationCells,
  creationWeekdays,
  creationWindows,
  isDefaultOpening,
  weekdayOf,
} from './stand-creation';

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

function jour(date: string): JourAmplitude {
  return {
    date,
    jour: 1,
    heureDebut: '10:00',
    heureFin: '20:00',
    minutes: 600,
    nombreCreneaux: 3,
    creneaux: [],
    ferie: null,
  };
}

// Sunday 12 and Monday 13 July 2026.
const COLONNES = ['2026-07-12', '2026-07-13'].flatMap((date, index) => [
  colonne(date, index * 10 + 1, '10:00:00', '12:00:00'),
  colonne(date, index * 10 + 2, '12:00:00', '14:00:00'),
  colonne(date, index * 10 + 3, '14:00:00', '20:00:00'),
]);

describe('stand creation', () => {
  it('offers every window of the edition, open at the minimum, and the weekdays of the event', () => {
    expect(
      creationWindows(COLONNES, 2).map((window) => [window.label, window.open, window.effectif]),
    ).toEqual([
      ['10-12', true, 2],
      ['12-14', true, 2],
      ['14-20', true, 2],
    ]);
    expect(weekdayOf('2026-07-13')).toBe(1);
    expect(creationWeekdays([jour('2026-07-12'), jour('2026-07-13')])).toEqual([
      { day: 1, open: true },
      { day: 0, open: true },
    ]);
  });

  it('writes « open 10–12 at 2, 14–20 at 4, closed on Mondays » as cells, nothing typed but numbers', () => {
    const windows = creationWindows(COLONNES, 2).map((window) =>
      window.label === '12-14'
        ? { ...window, open: false }
        : window.label === '14-20'
          ? { ...window, effectif: 4 }
          : window,
    );
    const weekdays = [
      { day: 1, open: false },
      { day: 0, open: true },
    ];

    const row = creationCells('S9', COLONNES, windows, weekdays).get('S9')!;
    expect([...row.values()]).toEqual([2, null, 4, null, null, null]);
    expect(creationBounds(windows, { min: 1, max: 1 })).toEqual({ min: 2, max: 4 });
    expect(isDefaultOpening(windows, weekdays, 2)).toBe(false);
  });

  it('knows the default opening needs no grid written', () => {
    const windows = creationWindows(COLONNES, 2);
    expect(isDefaultOpening(windows, creationWeekdays([jour('2026-07-12')]), 2)).toBe(true);
    expect(creationBounds([], { min: 1, max: 3 })).toEqual({ min: 1, max: 3 });
  });

  // Step 1 said one person; every window then typed at three: a stand of three,
  // which only the grid can say — the default would have kept it at one.
  it('writes the grid once every window was given another headcount than the one laid', () => {
    const windows = creationWindows(COLONNES, 1).map((window) => ({ ...window, effectif: 3 }));
    const weekdays = creationWeekdays([jour('2026-07-12')]);

    expect(isDefaultOpening(windows, weekdays, 1)).toBe(false);
    expect(creationBounds(windows, { min: 1, max: 1 })).toEqual({ min: 3, max: 3 });
  });
});
