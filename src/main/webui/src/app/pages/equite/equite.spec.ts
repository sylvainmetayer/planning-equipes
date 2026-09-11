// The pure rules of the Équité screen: the columns, a row's value in each, the
// distance to the median and its colour, the sort and the quick filter. No
// rendering: this is the half a table logic test can pin without the DOM.

import { describe, expect, it } from 'vitest';
import { LigneEquite, RapportEquite } from '../../core/models';
import {
  gapClass,
  colonnes,
  columnConstraint,
  medianGap,
  isWeek,
  filterRows,
  formatColonne,
  heureCourte,
  libelleColonne,
  libelleSolveur,
  sortRows,
  valeurColonne,
} from './equite';

function ligne(partial: Partial<LigneEquite> & { animateurId: string; nom: string }): LigneEquite {
  return {
    heuresTotal: 0,
    heuresParSemaine: {},
    heuresSoiree: 0,
    heuresWeekEnd: 0,
    heuresJourFerie: 0,
    postes: 0,
    postesPenibles: 0,
    standsDistincts: 0,
    typologiesDistinctes: 0,
    emplacementsDistinctsParJourMax: 0,
    tauxSouhaits: 0,
    tauxAppreciation: 0,
    joursTravailles: 0,
    joursRepos: 0,
    plusLongueSerie: 0,
    ...partial,
  };
}

const ALICE = ligne({
  animateurId: 'E2E-A',
  nom: 'Alice Émile',
  heuresTotal: 12,
  heuresParSemaine: { '2026-W28': 12 },
  heuresSoiree: 3,
  tauxSouhaits: 0.5,
});
const BRUNO = ligne({
  animateurId: 'E2E-B',
  nom: 'Bruno Zed',
  heuresTotal: 8,
  heuresParSemaine: { '2026-W29': 8 },
  heuresSoiree: 0,
  tauxSouhaits: 1,
});

const RAPPORT: RapportEquite = {
  heureDebutSoiree: '20:00:00',
  semaines: ['2026-W28', '2026-W29'],
  lignes: [ALICE, BRUNO],
  syntheses: {
    heuresTotal: { mediane: 10, min: 8, max: 12, ecartType: 2 },
    tauxSouhaits: { mediane: 0.75, min: 0.5, max: 1, ecartType: 0.25 },
  },
  colonnesSolveur: [
    { colonne: 'postes', contrainte: 'equilibrerCharge', active: true },
    { colonne: 'tauxSouhaits', contrainte: 'souhaitsIncompatibles', active: false },
  ],
};

describe('colonnes', () => {
  it('frames the weeks between the total and the other columns, the person first', () => {
    expect(colonnes(RAPPORT).slice(0, 4)).toEqual([
      'animateur',
      'heuresTotal',
      '2026-W28',
      '2026-W29',
    ]);
    expect(colonnes(RAPPORT)).toContain('plusLongueSerie');
    expect(colonnes(null)).toEqual(colonnes({ ...RAPPORT, semaines: [] }));
  });

  it('recognises an ISO week column and formats it as hours', () => {
    expect(isWeek('2026-W28')).toBe(true);
    expect(isWeek('heuresTotal')).toBe(false);
    expect(formatColonne('2026-W28')).toBe('heures');
    expect(formatColonne('tauxSouhaits')).toBe('taux');
    expect(formatColonne('postes')).toBe('nombre');
    expect(formatColonne('inconnue')).toBe('nombre');
  });

  it('names every fixed column and leaves a week as its own name', () => {
    for (const colonne of colonnes(RAPPORT)) {
      expect(libelleColonne(colonne), colonne).not.toBe('');
    }
    expect(libelleColonne('2026-W28')).toBe('2026-W28');
    expect(libelleColonne('animateur')).toBe('Animateur');
  });
});

describe('valeurColonne', () => {
  it('reads a fixed column from the row, and a week from heuresParSemaine, zero when absent', () => {
    expect(valeurColonne(ALICE, 'heuresTotal')).toBe(12);
    expect(valeurColonne(ALICE, '2026-W28')).toBe(12);
    expect(valeurColonne(ALICE, '2026-W29')).toBe(0);
    expect(valeurColonne(ALICE, 'colonneInconnue')).toBe(0);
  });
});

describe('medianGap', () => {
  it('is the value minus the median, and null without a synthesis', () => {
    expect(medianGap(12, RAPPORT.syntheses['heuresTotal'])).toBe(2);
    expect(medianGap(8, RAPPORT.syntheses['heuresTotal'])).toBe(-2);
    expect(medianGap(8, undefined)).toBeNull();
  });

  it('colours above and below the median, and nothing for a negligible or missing deviation', () => {
    expect(gapClass(2)).toBe('equite-ecart-positif');
    expect(gapClass(-0.5)).toBe('equite-ecart-negatif');
    expect(gapClass(0)).toBe('');
    expect(gapClass(0.001)).toBe('');
    expect(gapClass(null)).toBe('');
  });
});

describe('solver columns', () => {
  it('finds the rule measuring a column, and words its state', () => {
    expect(columnConstraint(RAPPORT, 'postes')?.contrainte).toBe('equilibrerCharge');
    expect(columnConstraint(RAPPORT, 'heuresSoiree')).toBeUndefined();
    expect(columnConstraint(null, 'postes')).toBeUndefined();

    expect(libelleSolveur(undefined)).toContain('non prise en compte');
    expect(libelleSolveur(columnConstraint(RAPPORT, 'postes'))).toContain('equilibrerCharge');
    expect(libelleSolveur(columnConstraint(RAPPORT, 'tauxSouhaits'))).toContain('désactivée');
  });
});

describe('sortRows', () => {
  it('keeps the source order while no sort is applied', () => {
    expect(sortRows([ALICE, BRUNO], { active: '', direction: '' })).toEqual([ALICE, BRUNO]);
    expect(sortRows([ALICE, BRUNO], { active: 'heuresTotal', direction: '' })).toEqual([
      ALICE,
      BRUNO,
    ]);
  });

  it('sorts by name, by a numeric column, by a week, and reverses on descending', () => {
    const noms = (lignes: LigneEquite[]) => lignes.map((row) => row.animateurId);
    expect(noms(sortRows([BRUNO, ALICE], { active: 'animateur', direction: 'asc' }))).toEqual([
      'E2E-A',
      'E2E-B',
    ]);
    expect(noms(sortRows([ALICE, BRUNO], { active: 'heuresTotal', direction: 'asc' }))).toEqual([
      'E2E-B',
      'E2E-A',
    ]);
    expect(noms(sortRows([ALICE, BRUNO], { active: '2026-W29', direction: 'desc' }))).toEqual([
      'E2E-B',
      'E2E-A',
    ]);
  });

  it('leaves the given array untouched', () => {
    const lignes = [ALICE, BRUNO];
    sortRows(lignes, { active: 'heuresTotal', direction: 'asc' });
    expect(lignes).toEqual([ALICE, BRUNO]);
  });
});

describe('filterRows', () => {
  it('matches the name or the id, accent- and case-insensitive, and everything on a blank filter', () => {
    expect(filterRows([ALICE, BRUNO], '')).toEqual([ALICE, BRUNO]);
    expect(filterRows([ALICE, BRUNO], 'emile')).toEqual([ALICE]);
    expect(filterRows([ALICE, BRUNO], 'e2e-b')).toEqual([BRUNO]);
    expect(filterRows([ALICE, BRUNO], 'personne')).toEqual([]);
  });
});

describe('heureCourte', () => {
  it('drops the seconds the server writes, and stays empty without a report', () => {
    expect(heureCourte('20:00:00')).toBe('20:00');
    expect(heureCourte(undefined)).toBe('');
  });
});
