// The pure half of the Réalisé vs planifié page: the grid built from the
// report, the colour of a cell, and the previous edition matched to a game
// category.

import { describe, expect, it } from 'vitest';
import { GapCounts, PreviousEdition, RealisedVsPlanned } from '../../core/models';
import { etatCase, grilleRealise, lateReferenceDays, unreferencedDays } from './realise';
import { mesurePrecedente } from './mesure';

function counts(overrides: Partial<GapCounts> = {}): GapCounts {
  return {
    publishedSeats: 2,
    keptSeats: 2,
    absences: 0,
    replacements: 0,
    emptySeats: 0,
    removedSeats: 0,
    addedSeats: 0,
    publishedMinutes: 240,
    realisedMinutes: 240,
    lostMinutes: 0,
    absenceRate: 0,
    replacementRate: 0,
    ...overrides,
  };
}

const ABSENCE = counts({ absences: 1, replacements: 1, absenceRate: 0.5, replacementRate: 0.5 });

const RAPPORT: RealisedVsPlanned = {
  referenceAvailable: true,
  nature: 'DECLARED',
  frozenPast: true,
  today: '2026-07-14',
  days: [
    { date: '2026-07-10', counted: false, referencePublishedAt: null, lateReference: false },
    {
      date: '2026-07-11',
      counted: true,
      referencePublishedAt: '2026-07-01T10:00:00Z',
      lateReference: false,
    },
    {
      date: '2026-07-12',
      counted: false,
      referencePublishedAt: '2026-07-12T09:00:00Z',
      lateReference: true,
    },
  ],
  cells: [
    { standId: 'cirque', standNom: 'Cirque', date: '2026-07-11', counts: ABSENCE },
    { standId: 'cirque', standNom: 'Cirque', date: '2026-07-12', counts: counts() },
    { standId: 'ninja', standNom: 'Ninja', date: '2026-07-12', counts: counts({ emptySeats: 1 }) },
  ],
  // The late day is summed nowhere: Ninja, drawn on it alone, has no total.
  byStand: [{ key: 'cirque', label: 'Cirque', counts: ABSENCE }],
  byDay: [{ key: '2026-07-11', label: '2026-07-11', counts: ABSENCE }],
  byTypologie: [],
  event: ABSENCE,
};

describe('grilleRealise', () => {
  it('draws one column per day with a reference, numbered on every elapsed day', () => {
    const grille = grilleRealise(RAPPORT);

    expect(grille.jours.map((jour) => jour.key)).toEqual(['2026-07-11', '2026-07-12']);
    expect(grille.jours.map((jour) => jour.label)).toEqual(['J2', 'J3']);
    expect(grille.jours[1].titre).toContain('non comptée');
  });

  it('draws one line per stand, a stand without a seat that day neutral and closed', () => {
    const grille = grilleRealise(RAPPORT);

    expect(grille.lignes.map((ligne) => ligne.id)).toEqual(['cirque', 'ninja']);
    const ninja = grille.lignes[1];
    expect(ninja.cases[0]).toEqual({ classe: 'realise-sans', libelle: '', active: false });
    expect(ninja.cases[1].active).toBe(true);
    expect(grille.lignes[0].cases[0].classe).toBe('realise-remplace');
  });

  it('flags the cells of a late day and leaves them out of every total', () => {
    const grille = grilleRealise(RAPPORT);

    const ninja = grille.lignes[1];
    expect(ninja.cases[1].classe).toBe('realise-vide realise-tardif');
    expect(ninja.cases[1].libelle).toContain('non comptée');
    expect(ninja.synthese['vides']?.texte).toBe('0');
    expect(grille.lignes[0].cases[1].classe).toBe('realise-tenu realise-tardif');
  });

  it('totals the absences over the published seats of each counted day in its footer', () => {
    const grille = grilleRealise(RAPPORT);

    expect(grille.pied?.cases).toEqual(['1/2', 'non comptée']);
    expect(grille.pied?.synthese['publies']).toBe('2');
  });

  it('names the days it does not count, and those measured against a late publication', () => {
    expect(unreferencedDays(RAPPORT)).toEqual(['2026-07-10']);
    expect(lateReferenceDays(RAPPORT)).toEqual(['2026-07-12']);
  });
});

describe('etatCase', () => {
  it('colours a cell by its worst gap', () => {
    expect(etatCase(counts({ emptySeats: 1, absences: 1 }))).toBe('realise-vide');
    expect(etatCase(counts({ replacements: 1 }))).toBe('realise-remplace');
    expect(etatCase(counts({ removedSeats: 1 }))).toBe('realise-retire');
    expect(etatCase(counts({ addedSeats: 1 }))).toBe('realise-ajoute');
    expect(etatCase(counts())).toBe('realise-tenu');
  });
});

describe('mesurePrecedente', () => {
  const PRECEDENTE: PreviousEdition = {
    available: true,
    editionId: 'E1',
    editionNom: 'Année 2025',
    firstDay: '2025-07-10',
    lastDay: '2025-07-20',
    countedDays: 11,
    frozenAt: '2025-07-21T01:30:00Z',
    event: counts(),
    byTypologie: [
      { key: 'ESCAPE', label: 'Escape game', counts: counts({ absences: 2 }) },
      { key: 'T7', label: 'Stratégie', counts: counts({ absences: 3 }) },
    ],
  };

  it('matches a game category by its name, case and accents aside', () => {
    expect(mesurePrecedente(PRECEDENTE, 'T9', 'strategie')?.counts.absences).toBe(3);
    expect(mesurePrecedente(PRECEDENTE, 'ESCAPE', 'Escape Game')?.counts.absences).toBe(2);
    expect(mesurePrecedente(PRECEDENTE, 'T9', 'Quiz')).toBeNull();
  });

  it('never matches by id alone: last year’s T7 may be another category', () => {
    expect(mesurePrecedente(PRECEDENTE, 'T7', 'Quiz')).toBeNull();
    expect(mesurePrecedente(PRECEDENTE, 'ESCAPE', 'Autre nom')).toBeNull();
  });

  it('breaks a tie between two categories of the same name by the id', () => {
    const doublon: PreviousEdition = {
      ...PRECEDENTE,
      byTypologie: [
        { key: 'T1', label: 'Stratégie', counts: counts({ absences: 1 }) },
        { key: 'T7', label: 'Stratégie', counts: counts({ absences: 3 }) },
      ],
    };
    expect(mesurePrecedente(doublon, 'T7', 'Stratégie')?.counts.absences).toBe(3);
    expect(mesurePrecedente(doublon, 'T9', 'Stratégie')?.counts.absences).toBe(1);
  });

  it('reads nothing when no earlier edition left a measure', () => {
    expect(
      mesurePrecedente({ ...PRECEDENTE, available: false }, 'ESCAPE', 'Escape game'),
    ).toBeNull();
    expect(mesurePrecedente(null, 'ESCAPE', 'Escape game')).toBeNull();
  });
});
