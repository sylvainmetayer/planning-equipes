import { describe, expect, it } from 'vitest';
import { CelluleTension, RapportTension } from '../../core/models';
import { buildSyntheseTension, buildTableTension, libelleMotif, niveauTension } from './tension';

function cellule(patch: Partial<CelluleTension>): CelluleTension {
  return {
    date: '2026-07-10',
    jour: 1,
    debut: '10:00:00',
    fin: '12:00:00',
    creneauId: 1,
    marge: 0,
    siegesVides: 0,
    siegesFragiles: 0,
    siegesIrremplacables: 0,
    competencesRaresSansSpecialiste: 0,
    animateursIrremplacables: [],
    standsSpecialisteUnique: [],
    standsSansSpecialiste: [],
    passee: false,
    gravite: 'CALME',
    motifs: [],
    ...patch,
  };
}

function rapport(): RapportTension {
  const critique = cellule({
    marge: 3,
    siegesFragiles: 2,
    siegesIrremplacables: 1,
    gravite: 'CRITIQUE',
    motifs: ['SIEGE_IRREMPLACABLE'],
  });
  const calme = cellule({ debut: '14:00:00', fin: '18:00:00', creneauId: 2, marge: 1 });
  const surveillee = cellule({
    date: '2026-07-11',
    jour: 2,
    creneauId: 3,
    marge: 0,
    siegesFragiles: 1,
    gravite: 'SURVEILLEE',
    motifs: ['SIEGES_FRAGILES'],
  });
  return {
    tranches: [
      { debut: '10:00:00', fin: '12:00:00' },
      { debut: '14:00:00', fin: '18:00:00' },
    ],
    jours: [
      { date: '2026-07-11', jour: 2, cellules: [surveillee], pireCellule: surveillee },
      { date: '2026-07-10', jour: 1, cellules: [critique, calme], pireCellule: critique },
    ],
    pireCellule: critique,
    animateursTotal: 4,
    cellulesCritiques: 1,
    ninjaConfigure: true,
    referentielsManquants: [],
    message: '',
  };
}

describe('tension', () => {
  it('lays the cells out on the margin grid, badge and grade included, a hole where there is no seat', () => {
    const table = buildTableTension(rapport());

    expect(table.colonnes.map((colonne) => colonne.label)).toEqual(['10:00-12:00', '14:00-18:00']);
    const [jour2, jour1] = table.lignes;
    expect(jour1.cellules.map((each) => [each.niveau, each.label, each.fragiles])).toEqual([
      ['critique', '+3', 2],
      ['calme', '+1', 0],
    ]);
    expect(jour2.cellules[1].niveau).toBe('vide');
    expect(jour1.cellules[0].description).toContain('Critique');
  });

  it('greys a started cell out, whatever the server rated', () => {
    expect(niveauTension(cellule({ passee: true, gravite: null }))).toBe('passee');
  });

  it('recalls the worst cell of each day, worst grade first', () => {
    expect(buildSyntheseTension(rapport()).map((ligne) => [ligne.jourLabel, ligne.niveau])).toEqual(
      [
        ['J1 · 2026-07-10', 'critique'],
        ['J2 · 2026-07-11', 'surveillee'],
      ],
    );
  });

  it('words every reason with the cell’s own figures', () => {
    const critique = cellule({ siegesIrremplacables: 2 });
    expect(libelleMotif('SIEGE_IRREMPLACABLE', critique)).toBe(
      "2 siège(s) que personne d'autre ne pourrait reprendre",
    );
  });
  // A « +68 » painted in the error colour read as a shortage of sixty-eight:
  // the fill is the margin's sign, the grade a frame on top of it.
  it('colours a cell by the sign of its margin, its grade apart', () => {
    const [, jour1] = buildTableTension(rapport()).lignes;

    expect(jour1.cellules.map((each) => [each.marge, each.niveau])).toEqual([
      ['surplusFort', 'critique'],
      ['surplus', 'calme'],
    ]);
    // The reasons travel with the cell, for the tooltip and the screen reader.
    expect(jour1.cellules[0].description).toContain("que personne d'autre ne pourrait reprendre");
  });

  it('greys a started cell whatever its margin, and draws a hole without a fill', () => {
    const table = buildTableTension({
      ...rapport(),
      jours: [
        {
          date: '2026-07-10',
          jour: 1,
          cellules: [cellule({ passee: true, gravite: null, marge: -4 })],
          pireCellule: null,
        },
      ],
    });

    expect(table.lignes[0].cellules.map((each) => each.marge)).toEqual(['vide', 'vide']);
  });
});
