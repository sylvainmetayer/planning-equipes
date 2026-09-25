import { describe, expect, it } from 'vitest';

import { MuralShift, MuralStand } from '../../core/models';
import {
  advance,
  groupByEmplacement,
  heureOf,
  minutesSince,
  momentOf,
  nextPage,
  pageLabel,
  paginate,
  secondsSince,
  shiftKey,
  tilesPerPage,
} from './mural';

function shift(start: string, end: string, noms: string[] = []): MuralShift {
  return { start, end, noms, emptySeats: 0 };
}

function stand(id: string, emplacementNom: string | null, vacations: MuralShift[]): MuralStand {
  return { standId: id, standNom: id, emplacementId: null, emplacementNom, vacations };
}

const JOUR = '2026-07-08';

describe('mural — the shift under way and the next one', () => {
  const jeux = stand('jeux', 'Nord', [
    shift(`${JOUR}T14:00:00`, `${JOUR}T18:00:00`),
    shift(`${JOUR}T09:00:00`, `${JOUR}T13:00:00`),
  ]);

  it('reads both against the moment given, whatever order the shifts came in', () => {
    const moment = momentOf(jeux, `${JOUR}T10:00:00`);

    expect(moment.current.map((each) => each.start)).toEqual([`${JOUR}T09:00:00`]);
    expect(moment.next?.start).toBe(`${JOUR}T14:00:00`);
  });

  it('has no shift under way between two shifts, and names the reopening', () => {
    const moment = momentOf(jeux, `${JOUR}T13:30:00`);

    expect(moment.current).toEqual([]);
    expect(moment.next?.start).toBe(`${JOUR}T14:00:00`);
  });

  it('counts a shift as over at its end minute', () => {
    expect(momentOf(jeux, `${JOUR}T13:00:00`).current).toEqual([]);
    expect(momentOf(jeux, `${JOUR}T18:00:00`).next).toBeNull();
  });

  it('keeps a shift crossing midnight under way after 0:00', () => {
    const bar = stand('bar', null, [shift(`${JOUR}T22:00:00`, '2026-07-09T02:00:00')]);

    expect(momentOf(bar, '2026-07-09T01:00:00').current[0]?.start).toBe(`${JOUR}T22:00:00`);
    expect(momentOf(bar, '2026-07-09T02:00:00').current).toEqual([]);
  });

  it('shows every shift under way when two overlap on one stand, and the earliest next one', () => {
    const accueil = stand('accueil', null, [
      shift(`${JOUR}T16:00:00`, `${JOUR}T20:00:00`, ['Noé']),
      shift(`${JOUR}T12:00:00`, `${JOUR}T16:00:00`, ['Léo']),
      shift(`${JOUR}T10:00:00`, `${JOUR}T14:00:00`, ['Camille']),
      shift(`${JOUR}T15:00:00`, `${JOUR}T19:00:00`, ['Inès']),
    ]);

    const moment = momentOf(accueil, `${JOUR}T13:00:00`);

    expect(moment.current.flatMap((each) => each.noms)).toEqual(['Camille', 'Léo']);
    expect(moment.next?.noms).toEqual(['Inès']);
  });

  it('tells two shifts of one stand apart by their window', () => {
    expect(shiftKey(shift(`${JOUR}T10:00:00`, `${JOUR}T14:00:00`))).not.toBe(
      shiftKey(shift(`${JOUR}T10:00:00`, `${JOUR}T12:00:00`)),
    );
  });
});

describe('mural — the server clock, advanced locally', () => {
  it('adds the elapsed time to the server moment, across midnight', () => {
    expect(advance(`${JOUR}T23:59:30`, 45_000)).toBe('2026-07-09T00:00:15');
    expect(advance(`${JOUR}T10:00:00`, -5_000)).toBe(`${JOUR}T10:00:00`);
  });

  it('prints the time of day of a moment', () => {
    expect(heureOf(`${JOUR}T09:05:00`)).toBe('09:05');
  });

  it('counts the minutes and seconds since the last good read', () => {
    expect(minutesSince(0, 3 * 60_000 + 59_000)).toBe(3);
    expect(secondsSince(0, 40_500)).toBe(40);
    expect(minutesSince(10_000, 0)).toBe(0);
  });
});

describe('mural — pagination', () => {
  it('cuts the tiles into pages and always keeps one', () => {
    expect(paginate([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(paginate([], 4)).toEqual([[]]);
    expect(paginate([1, 2], 0)).toEqual([[1], [2]]);
  });

  it('turns the pages round and labels them only when there are several', () => {
    expect(nextPage(0, 3)).toBe(1);
    expect(nextPage(2, 3)).toBe(0);
    expect(nextPage(0, 1)).toBe(0);
    expect(pageLabel(0, 3)).toBe('1/3');
    expect(pageLabel(0, 1)).toBeNull();
  });

  it('fits more tiles on a larger screen, and at least one on any', () => {
    expect(tilesPerPage(1920, 1080)).toBe(6);
    expect(tilesPerPage(1080, 1920)).toBe(8);
    expect(tilesPerPage(300, 200)).toBe(1);
  });
});

describe('mural — emplacement groups', () => {
  it('heads consecutive tiles of the same emplacement once', () => {
    const at = `${JOUR}T10:00:00`;
    const groupes = groupByEmplacement(
      [
        stand('a', 'Nord', []),
        stand('b', 'Nord', []),
        stand('c', 'Sud', []),
        stand('d', null, []),
      ].map((each) => momentOf(each, at)),
    );

    expect(groupes.map((groupe) => groupe.emplacementNom)).toEqual(['Nord', 'Sud', null]);
    expect(groupes[0].stands.map((moment) => moment.stand.standId)).toEqual(['a', 'b']);
  });
});
