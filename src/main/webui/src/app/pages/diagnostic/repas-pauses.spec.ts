import { describe, expect, it } from 'vitest';
import { RapportPauses, WalkSequenceReport, WalkView } from '../../core/models';
import { walksOfDay, joursRepasPauses } from './repas-pauses';

function walk(overrides: Partial<WalkView>): WalkView {
  return {
    animateurId: 'alice',
    date: '2026-07-10',
    end: '12:00:00',
    start: '12:10:00',
    distanceMetres: 400,
    walkMinutes: 6,
    gapMinutes: 10,
    missingMinutes: 0,
    walkOnBreak: false,
    ...overrides,
  };
}

const WALKS: WalkSequenceReport = {
  walkingSpeedKmH: 4,
  detourFactor: 1.3,
  toleranceMinutes: 0,
  geolocated: true,
  walks: [
    walk({ fromPosteId: 'a', end: '15:00:00' }),
    walk({ fromPosteId: 'b', end: '11:00:00', missingMinutes: 0 }),
    walk({ fromPosteId: 'c', end: '16:00:00', missingMinutes: 3 }),
    walk({ fromPosteId: 'd', date: '2026-07-12' }),
  ],
};

describe('walksOfDay', () => {
  it('keeps the day asked, the walks that do not fit first, then by time', () => {
    const rows = walksOfDay(WALKS, '2026-07-10').map((w) => w.fromPosteId);
    expect(rows).toEqual(['c', 'b', 'a']);
  });

  it('is empty without a day or a report', () => {
    expect(walksOfDay(WALKS, null)).toEqual([]);
    expect(walksOfDay(null, '2026-07-10')).toEqual([]);
  });
});

describe('joursRepasPauses', () => {
  it('unites the days of the breaks and of the walks, each once, in order', () => {
    const journee = (date: string, jour: number): RapportPauses['journees'][number] => ({
      animateurId: 'alice',
      nomComplet: 'Alice Martin',
      mineur: false,
      date,
      jour,
      sequences: [],
      pausesPlanifiees: [],
      coupuresRepas: [],
    });
    const rapport: RapportPauses = {
      journeesAnalysees: 2,
      pausesDues: 0,
      relaisManquants: 0,
      coupuresRepasDues: 0,
      coupuresRepasManquantes: 0,
      message: '',
      journees: [journee('2026-07-11', 2), journee('2026-07-10', 1)],
    };
    expect(joursRepasPauses(rapport, WALKS)).toEqual(['2026-07-10', '2026-07-11', '2026-07-12']);
    expect(joursRepasPauses(null, null)).toEqual([]);
  });
});
