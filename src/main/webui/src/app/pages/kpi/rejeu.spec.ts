import { describe, expect, it } from 'vitest';
import { KpiHistoriqueEntry, PlanningKpi } from '../../core/models';
import { HAUTEUR_COURBE, LARGEUR_COURBE } from '../solver/score-curve';
import {
  clampRank,
  coverageBand,
  editionsOfHistory,
  kpiDelta,
  playbackStep,
  rankX,
  replaySeries,
  resolutionsOfEdition,
} from './rejeu';

function kpi(overrides: Partial<PlanningKpi> = {}): PlanningKpi {
  return {
    score: '-2hard/-100medium/-5soft',
    scoreHard: -2,
    scoreMedium: -100,
    scoreSoft: -5,
    postesTotal: 10,
    postesPourvus: 8,
    animateursAffectes: 5,
    standsDistincts: 2,
    creneauxDistincts: 4,
    heuresTotal: 40,
    heuresMoyenne: 8,
    heuresEcartType: 1.5,
    heuresMin: 6,
    heuresMax: 10,
    heuresIncompletes: false,
    modificationsManuelles: 0,
    tauxModificationsManuelles: 0,
    dureeSolveSecondes: 60,
    violationsParContrainte: {},
    scoreMediumHorsPlancher: -80,
    plancherMedium: -20,
    journeesSousConsigne: null,
    heuresFermeesParConsigne: null,
    ...overrides,
  };
}

function entry(
  id: number,
  editionId: string,
  creeLe: string,
  overrides: Partial<PlanningKpi> = {},
): KpiHistoriqueEntry {
  return { id, editionId, editionNom: `Nom ${editionId}`, kpi: kpi(overrides), creeLe };
}

const HISTORY = [
  entry(3, 'E26', '2026-09-03T10:00:00Z'),
  entry(1, 'E26', '2026-09-01T10:00:00Z'),
  entry(9, 'SUPPRIMEE', '2025-09-01T10:00:00Z'),
  entry(2, 'E26', '2026-09-02T10:00:00Z'),
];

describe('rejeu', () => {
  it('lists every edition the history holds, deleted ones included, most solves first', () => {
    expect(editionsOfHistory(HISTORY)).toEqual([
      { editionId: 'E26', editionNom: 'Nom E26', count: 3 },
      { editionId: 'SUPPRIMEE', editionNom: 'Nom SUPPRIMEE', count: 1 },
    ]);
  });

  it("replays one edition's solves oldest first", () => {
    expect(resolutionsOfEdition(HISTORY, 'E26').map((each) => each.id)).toEqual([1, 2, 3]);
    expect(resolutionsOfEdition(HISTORY, 'INCONNUE')).toEqual([]);
  });

  it('points at the latest solve by default, and keeps a requested rank within bounds', () => {
    expect(clampRank(null, 3)).toBe(2);
    expect(clampRank(0, 3)).toBe(0);
    expect(clampRank(12, 3)).toBe(2);
    expect(clampRank(-4, 3)).toBe(0);
    expect(clampRank(null, 0)).toBe(0);
  });

  it('plays one by one, and five by five past a hundred solves', () => {
    expect(playbackStep(100)).toBe(1);
    expect(playbackStep(101)).toBe(5);
  });

  it('says what moved since the previous solve, rules appearing and disappearing included', () => {
    const before = kpi({ violationsParContrainte: { equilibrerCharge: 4, reposQuotidien: 2 } });
    const after = kpi({
      postesPourvus: 10,
      scoreHard: 0,
      scoreMediumHorsPlancher: -200,
      violationsParContrainte: { equilibrerCharge: 1, pauseSansRelais: 3 },
    });

    const delta = kpiDelta(before, after)!;

    expect(delta.postesPourvus).toBe(2);
    expect(delta.scoreHard).toBe(2);
    expect(delta.scoreMedium).toBe(-120);
    expect(delta.mediumNetOfFloor).toBe(true);
    expect(delta.rules).toEqual([
      { name: 'pauseSansRelais', before: 0, after: 3, kind: 'appeared' },
      { name: 'reposQuotidien', before: 2, after: 0, kind: 'disappeared' },
      { name: 'equilibrerCharge', before: 4, after: 1, kind: 'changed' },
    ]);
    expect(kpiDelta(null, after)).toBeNull();
  });

  it('falls back to the raw medium score when either solve did not measure its floor', () => {
    const delta = kpiDelta(kpi({ scoreMediumHorsPlancher: null }), kpi({ scoreMedium: -90 }))!;
    expect(delta.scoreMedium).toBe(10);
    expect(delta.mediumNetOfFloor).toBe(false);
  });

  it('draws each figure on its own scale, by rank, a solve without a score keeping its rank', () => {
    const resolutions = [
      entry(1, 'E', '2026-09-01T10:00:00Z', { scoreHard: -10 }),
      entry(2, 'E', '2026-09-02T10:00:00Z', { scoreHard: null, score: null }),
      entry(3, 'E', '2026-09-03T10:00:00Z', { scoreHard: 0 }),
    ];

    const hard = replaySeries(resolutions).find((serie) => serie.figure === 'hard')!;

    expect(hard.points.map((point) => point.rank)).toEqual([0, 2]);
    expect(hard.points[0]).toEqual({ x: 0, y: HAUTEUR_COURBE, rank: 0 });
    expect(hard.points[1]).toEqual({ x: LARGEUR_COURBE, y: 0, rank: 2 });
    expect(hard.top).toBe(0);
    expect(hard.bottom).toBe(-10);
  });

  it('scales coverage on 0 to 100 % and the spread of hours on what it reached', () => {
    const resolutions = [
      entry(1, 'E', '2026-09-01T10:00:00Z', { postesPourvus: 5, heuresEcartType: 2 }),
      entry(2, 'E', '2026-09-02T10:00:00Z', { postesPourvus: 10, heuresEcartType: 4 }),
    ];
    const [, , , coverage, fairness] = replaySeries(resolutions);

    expect(coverage.top).toBe(100);
    expect(coverage.points.map((point) => point.y)).toEqual([HAUTEUR_COURBE / 2, 0]);
    expect(fairness.bottom).toBe(2);
    expect(fairness.top).toBe(4);
  });

  it('centres a single solve rather than pinning it to an edge', () => {
    expect(rankX(0, 1)).toBe(LARGEUR_COURBE / 2);
  });

  it('reads the day × coverage band in calendar order, and says nothing for an older row', () => {
    const band = coverageBand(
      kpi({
        couvertureParJour: {
          '2026-07-07': { postes: 4, pourvus: 2 },
          '2026-07-06': { postes: 2, pourvus: 2 },
        },
      }),
    );
    expect(band).toEqual([
      { date: '2026-07-06', postes: 2, pourvus: 2, ratio: 1 },
      { date: '2026-07-07', postes: 4, pourvus: 2, ratio: 0.5 },
    ]);
    expect(coverageBand(kpi())).toBeNull();
  });
});
