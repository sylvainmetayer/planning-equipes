// The view logic of « Marge disponible » is pure and tested as such: the
// margin itself is the server's answer, so what is worth proving here is the
// layout — that a day missing a timeslot keeps its columns aligned — and the
// two readings the screen adds on top, the divergent scale and where a cell
// leads.

import { describe, expect, it } from 'vitest';
import { CelluleMarge, JourMarge, RapportMarge } from '../../core/models';
import {
  buildSynthese,
  buildTable,
  lienCellule,
  libelleTranche,
  niveauMarge,
  signe,
} from './marge';

function cellule(overrides: Partial<CelluleMarge> = {}): CelluleMarge {
  return {
    date: '2026-07-10',
    jour: 1,
    debut: '09:00:00',
    fin: '12:00:00',
    creneauId: 1,
    sieges: 2,
    siegesPourvus: 0,
    besoin: 2,
    disponibles: 3,
    marge: 1,
    ...overrides,
  };
}

function jour(date: string, numero: number, cellules: CelluleMarge[]): JourMarge {
  return {
    date,
    jour: numero,
    cellules,
    pireCellule: cellules.length === 0 ? null : cellules[0],
  };
}

function rapport(overrides: Partial<RapportMarge> = {}): RapportMarge {
  return {
    mode: 'AVANT',
    tranches: [{ debut: '09:00:00', fin: '12:00:00' }],
    jours: [jour('2026-07-10', 1, [cellule()])],
    animateursTotal: 3,
    cellulesDeficitaires: 0,
    pireCellule: cellule(),
    pauseMinimaleMinutes: 30,
    referentielsManquants: [],
    message: 'Aucune tranche en déficit.',
    ...overrides,
  };
}

describe('niveauMarge', () => {
  it('reads zero as its own step, neither a shortage nor a comfort', () => {
    expect(niveauMarge(0)).toBe('neutre');
  });

  it('separates a shortage from a marked one, and a margin from a comfortable one', () => {
    expect(niveauMarge(-1)).toBe('deficit');
    expect(niveauMarge(-2)).toBe('deficit');
    expect(niveauMarge(-3)).toBe('deficitFort');
    expect(niveauMarge(-12)).toBe('deficitFort');
    expect(niveauMarge(1)).toBe('surplus');
    expect(niveauMarge(3)).toBe('surplusFort');
  });
});

describe('signe', () => {
  it('writes the plus out, since the sign is the whole reading', () => {
    expect(signe(2)).toBe('+2');
    expect(signe(0)).toBe('0');
    expect(signe(-2)).toBe('-2');
  });
});

describe('libelleTranche', () => {
  it('drops the seconds the API always sends as zero', () => {
    expect(libelleTranche('09:00:00', '12:00:00')).toBe('09:00-12:00');
  });
});

describe('buildTable', () => {
  it('has nothing to lay out without a report', () => {
    expect(buildTable(null)).toEqual({ colonnes: [], lignes: [] });
  });

  it('draws one row per day and one column per timeslot of the grid', () => {
    const table = buildTable(
      rapport({
        tranches: [
          { debut: '09:00:00', fin: '12:00:00' },
          { debut: '20:00:00', fin: '23:00:00' },
        ],
        jours: [
          jour('2026-07-10', 1, [
            cellule(),
            cellule({ debut: '20:00:00', fin: '23:00:00', creneauId: 2, marge: -4 }),
          ]),
        ],
      }),
    );

    expect(table.colonnes.map((colonne) => colonne.label)).toEqual(['09:00-12:00', '20:00-23:00']);
    expect(table.lignes).toHaveLength(1);
    expect(table.lignes[0].label).toBe('J1 · 2026-07-10');
    expect(table.lignes[0].cellules.map((each) => each.label)).toEqual(['+1', '-4']);
    expect(table.lignes[0].cellules[1].niveau).toBe('deficitFort');
  });

  it('keeps the columns aligned when a day holds no seat on one of them', () => {
    // Without a placeholder the evening cell of the second day would slide
    // under the morning column, which is the one bug a grid cannot show.
    const table = buildTable(
      rapport({
        tranches: [
          { debut: '09:00:00', fin: '12:00:00' },
          { debut: '20:00:00', fin: '23:00:00' },
        ],
        jours: [
          jour('2026-07-10', 1, [
            cellule(),
            cellule({ debut: '20:00:00', fin: '23:00:00', creneauId: 2, marge: -1 }),
          ]),
          jour('2026-07-11', 2, [
            cellule({
              date: '2026-07-11',
              jour: 2,
              debut: '20:00:00',
              fin: '23:00:00',
              creneauId: 3,
              marge: 2,
            }),
          ]),
        ],
      }),
    );

    const seconde = table.lignes[1].cellules;
    expect(seconde).toHaveLength(2);
    expect(seconde[0]).toMatchObject({ niveau: 'vide', label: '', creneauId: null });
    expect(seconde[1]).toMatchObject({ niveau: 'surplus', label: '+2', creneauId: 3 });
  });

  it('spells the counts out in the tooltip, in the words of the mode on screen', () => {
    const brut = buildTable(rapport()).lignes[0].cellules[0];
    expect(brut.tooltip).toContain('J1 · 2026-07-10');
    expect(brut.tooltip).toContain('09:00-12:00');
    expect(brut.tooltip).toContain('à pourvoir');

    const resolu = buildTable(
      rapport({
        mode: 'APRES',
        jours: [
          jour('2026-07-10', 1, [
            cellule({ siegesPourvus: 1, besoin: 1, disponibles: 2, marge: 1 }),
          ]),
        ],
      }),
    ).lignes[0].cellules[0];
    expect(resolu.tooltip).toContain('vide(s)');
  });

  it('says « aucun siège » on a cell the grid holds nothing on', () => {
    const table = buildTable(
      rapport({
        tranches: [
          { debut: '09:00:00', fin: '12:00:00' },
          { debut: '20:00:00', fin: '23:00:00' },
        ],
      }),
    );

    expect(table.lignes[0].cellules[1].tooltip).toContain('aucun siège');
  });
});

describe('buildSynthese', () => {
  it('gives one line per day, naming the timeslot the server picked as the worst', () => {
    const pire = cellule({ debut: '20:00:00', fin: '23:00:00', creneauId: 2, marge: -2 });
    const lignes = buildSynthese(
      rapport({
        jours: [{ date: '2026-07-10', jour: 1, cellules: [cellule(), pire], pireCellule: pire }],
      }),
    );

    expect(lignes).toHaveLength(1);
    expect(lignes[0]).toMatchObject({
      jourLabel: 'J1 · 2026-07-10',
      trancheLabel: '20:00-23:00',
      marge: -2,
      niveau: 'deficit',
      creneauId: 2,
    });
  });

  it('skips a day the server left without a worst cell rather than inventing one', () => {
    expect(buildSynthese(rapport({ jours: [jour('2026-07-10', 1, [])] }))).toEqual([]);
  });
});

describe('lienCellule', () => {
  it('opens the bench on the cell timeslot once a plan exists', () => {
    expect(lienCellule('APRES', 42, '2026-07-10')).toEqual({
      route: '/diagnostic',
      queryParams: { onglet: 'banc', creneau: 42 },
    });
  });

  it('opens that day of the stand openings before a solve, where the margin is still moved', () => {
    expect(lienCellule('AVANT', 42, '2026-07-10')).toEqual({
      route: '/ouvertures',
      queryParams: { vue: 'journee', date: '2026-07-10' },
    });
  });

  it('leads nowhere from a cell holding no seat, rather than to an empty screen', () => {
    expect(lienCellule('APRES', null, null)).toBeNull();
    expect(lienCellule('AVANT', null, null)).toBeNull();
  });
});
