import { describe, expect, it } from 'vitest';
import { PosteAffectation } from '../../core/models';
import { JourEvenement } from './journee';
import { emptySeatsByDate, libelleCase, eventMonths } from './mois';

function jour(numero: number, date: string | null): JourEvenement {
  return { jour: numero, date, key: date ?? `J${numero}`, title: `Jour ${numero}` };
}

const AUCUNE_MARQUE = { relu: () => false, verrou: () => false, consigne: () => false };

describe('the day selector of the Planning page', () => {
  it('lays the event out as the months it falls in, weeks starting on Monday', () => {
    const mois = eventMonths(
      [jour(1, '2026-08-31'), jour(2, '2026-09-01')],
      new Map(),
      AUCUNE_MARQUE,
      null,
    );

    expect(mois.map((un) => un.cle)).toEqual(['2026-08', '2026-09']);
    // 2026-09-01 is a Tuesday: the first week of September opens on Monday 31/08.
    const firstWeek = mois[1].semaines[0];
    expect(firstWeek[0].date).toBe('2026-08-31');
    expect(firstWeek[0].horsMois).toBe(true);
    expect(firstWeek[1].jour?.jour).toBe(2);
    // A week lying wholly in October is not drawn.
    expect(mois[1].semaines.flat().every((cellule) => cellule.date < '2026-10-05')).toBe(true);
  });

  it('marks each event day with its empty seats, its reading, its lock, its consigne', () => {
    const [mois] = eventMonths(
      [jour(1, '2026-09-05'), jour(2, '2026-09-06')],
      new Map([['2026-09-05', 3]]),
      {
        relu: (date) => date === '2026-09-06',
        verrou: (date) => date === '2026-09-05',
        consigne: (date) => date === '2026-09-05',
      },
      '2026-09-06',
    );
    const cases = mois.semaines.flat().filter((cellule) => cellule.jour);

    expect(
      cases.map((cellule) => [cellule.vides, cellule.relu, cellule.verrou, cellule.consigne]),
    ).toEqual([
      [3, false, true, true],
      [0, true, false, false],
    ]);
    // Today is the server's, whichever day is on screen.
    expect(cases.map((cellule) => cellule.aujourdhui)).toEqual([false, true]);
    expect(libelleCase(cases[0])).toBe('Jour 1 · 3 siège(s) vide(s) · verrouillée · sous consigne');
    expect(libelleCase(cases[1])).toBe("Jour 2 · aujourd'hui · relue et acceptée");
  });

  it('draws no month for timeslots that carry no date', () => {
    expect(eventMonths([jour(1, null)], new Map(), AUCUNE_MARQUE, null)).toEqual([]);
  });

  it('counts the seats nobody holds, date by date', () => {
    const stand = { id: 'S' } as PosteAffectation['stand'];
    const postes = [
      { id: 'p1', stand, creneau: { id: 1, jour: 1, date: '2026-09-05' }, animateur: null },
      { id: 'p2', stand, creneau: { id: 1, jour: 1, date: '2026-09-05' }, animateur: null },
      { id: 'p3', stand, creneau: { id: 2, jour: 2, date: '2026-09-06' }, animateur: { id: 'a' } },
    ] as PosteAffectation[];

    expect([...emptySeatsByDate(postes)]).toEqual([['2026-09-05', 2]]);
  });
});
