import { describe, expect, it } from 'vitest';
import { Creneau, Stand } from '../../core/models';
import { computeStaffingSummary } from './staffing';

function stand(overrides: Partial<Stand> & { id: string }): Stand {
  return {
    nom: overrides.id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    ...overrides
  };
}

function creneau(overrides: Partial<Creneau> & { id: string }): Creneau {
  return {
    jour: 1,
    date: '2026-08-01',
    heureDebut: '09:00',
    heureFin: '12:00',
    standsOuvertsIds: [],
    ...overrides
  };
}

describe('computeStaffingSummary', () => {
  it('splits an open stand majeurs >= mineurs, ceil/floor', () => {
    const summary = computeStaffingSummary([stand({ id: 's1', effectifMin: 3 })], [creneau({ id: 'c1' })]);
    expect(summary.parCreneau).toEqual([
      expect.objectContaining({ creneauId: 'c1', standsOuverts: 1, total: 3, majeurs: 2, mineurs: 1 })
    ]);
  });

  it('forces every seat to majeurs on a stand reserved to majeurs', () => {
    const summary = computeStaffingSummary(
      [stand({ id: 's1', effectifMin: 4, reserveMajeurs: true })],
      [creneau({ id: 'c1' })]
    );
    expect(summary.parCreneau[0]).toMatchObject({ total: 4, majeurs: 4, mineurs: 0 });
  });

  it('treats effectifMin of 0 as at least one seat, mirroring the backend poste generation', () => {
    const summary = computeStaffingSummary([stand({ id: 's1', effectifMin: 0 })], [creneau({ id: 'c1' })]);
    expect(summary.parCreneau[0]).toMatchObject({ total: 1, majeurs: 1, mineurs: 0 });
  });

  it('restricts open stands to standsOuvertsIds when non-empty, and all stands when empty', () => {
    const stands = [stand({ id: 's1', effectifMin: 2 }), stand({ id: 's2', effectifMin: 5 })];
    const summary = computeStaffingSummary(stands, [
      creneau({ id: 'c-restricted', standsOuvertsIds: ['s1'] }),
      creneau({ id: 'c-all', standsOuvertsIds: [] })
    ]);
    const restricted = summary.parCreneau.find((row) => row.creneauId === 'c-restricted');
    const all = summary.parCreneau.find((row) => row.creneauId === 'c-all');
    expect(restricted).toMatchObject({ standsOuverts: 1, total: 2 });
    expect(all).toMatchObject({ standsOuverts: 2, total: 7 });
  });

  it('ignores stand ids in standsOuvertsIds that no longer exist', () => {
    const summary = computeStaffingSummary(
      [stand({ id: 's1', effectifMin: 2 })],
      [creneau({ id: 'c1', standsOuvertsIds: ['s1', 'ghost'] })]
    );
    expect(summary.parCreneau[0]).toMatchObject({ standsOuverts: 1, total: 2 });
  });

  it('picks the busiest créneau as the minimum global headcount, not the sum across créneaux', () => {
    const stands = [stand({ id: 's1', effectifMin: 2 })];
    const summary = computeStaffingSummary(stands, [
      creneau({ id: 'c-light', date: '2026-08-01', standsOuvertsIds: ['s1'] }),
      creneau({ id: 'c-peak', date: '2026-08-02', standsOuvertsIds: ['s1'] })
    ]);
    expect(summary.minimumTotal).toBe(2);
    expect(summary.creneauCritique?.creneauId).toBeDefined();
  });

  it('returns zeros and no critical créneau when there is nothing to plan', () => {
    const summary = computeStaffingSummary([], []);
    expect(summary).toMatchObject({ minimumTotal: 0, minimumMajeurs: 0, minimumMineurs: 0, creneauCritique: null });
  });

  it('sorts the per-créneau rows by date then start time', () => {
    const summary = computeStaffingSummary(
      [],
      [
        creneau({ id: 'later', date: '2026-08-02', heureDebut: '09:00' }),
        creneau({ id: 'earlier-same-day', date: '2026-08-01', heureDebut: '09:00' }),
        creneau({ id: 'earlier', date: '2026-08-01', heureDebut: '14:00' })
      ]
    );
    expect(summary.parCreneau.map((row) => row.creneauId)).toEqual(['earlier-same-day', 'earlier', 'later']);
  });
});
