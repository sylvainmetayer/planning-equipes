import { describe, expect, it } from 'vitest';
import { Creneau, IndisponibiliteStand, OuvertureStand, Stand } from '../../core/models';
import { computeStaffingSummary } from './staffing';

function stand(overrides: Partial<Stand> & { id: string }): Stand {
  return {
    nom: overrides.id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    ...overrides
  };
}

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return {
    jour: 1,
    date: '2026-08-01',
    heureDebut: '09:00',
    heureFin: '12:00',
    groupe: null,
    ...overrides
  };
}

/** Closure covering a créneau's whole [heureDebut, heureFin) window on its date. */
function fermetureIntegrale(creneau: Creneau): IndisponibiliteStand {
  return { id: null, date: creneau.date, heureDebut: creneau.heureDebut, heureFin: creneau.heureFin, motif: null };
}

/** Opening covering a créneau's whole [heureDebut, heureFin) window on its date. */
function ouvertureIntegrale(creneau: Creneau): OuvertureStand {
  return { id: null, date: creneau.date, heureDebut: creneau.heureDebut, heureFin: creneau.heureFin, motif: null };
}

describe('computeStaffingSummary — per-créneau seats', () => {
  it('splits an open stand majeurs >= mineurs, ceil/floor', () => {
    const summary = computeStaffingSummary([stand({ id: 's1', effectifMin: 3 })], [creneau({ id: 1 })]);
    expect(summary.parCreneau).toEqual([
      expect.objectContaining({ creneauId: 1, standsOuverts: 1, total: 3, majeurs: 2, mineurs: 1 })
    ]);
  });

  it('forces every seat to majeurs on a stand reserved to majeurs', () => {
    const summary = computeStaffingSummary(
      [stand({ id: 's1', effectifMin: 4, reserveMajeurs: true })],
      [creneau({ id: 1 })]
    );
    expect(summary.parCreneau[0]).toMatchObject({ total: 4, majeurs: 4, mineurs: 0 });
  });

  it('treats effectifMin of 0 as at least one seat, mirroring the backend poste generation', () => {
    const summary = computeStaffingSummary([stand({ id: 's1', effectifMin: 0 })], [creneau({ id: 1 })]);
    expect(summary.parCreneau[0]).toMatchObject({ total: 1, majeurs: 1, mineurs: 0 });
  });

  it('excludes a stand closed for the whole créneau from the demand', () => {
    const c1 = creneau({ id: 1 });
    const c2 = creneau({ id: 2, date: '2026-08-02' });
    const s1 = stand({ id: 's1', effectifMin: 2 });
    const s2 = stand({ id: 's2', effectifMin: 5, indisponibilites: [fermetureIntegrale(c1)] });
    const summary = computeStaffingSummary([s1, s2], [c1, c2]);
    const restricted = summary.parCreneau.find((row) => row.creneauId === 1);
    const all = summary.parCreneau.find((row) => row.creneauId === 2);
    expect(restricted).toMatchObject({ standsOuverts: 1, total: 2 });
    expect(all).toMatchObject({ standsOuverts: 2, total: 7 });
  });

  it('still counts a stand only partially closed for the créneau — this coarse estimate is not per-minute', () => {
    const c1 = creneau({ id: 1, heureDebut: '09:00', heureFin: '14:00' });
    const s1 = stand({
      id: 's1',
      effectifMin: 2,
      indisponibilites: [{ id: null, date: c1.date, heureDebut: '11:00', heureFin: '13:00', motif: null }]
    });
    const summary = computeStaffingSummary([s1], [c1]);
    expect(summary.parCreneau[0]).toMatchObject({ standsOuverts: 1, total: 2 });
  });

  it('ignores a closure dated on a different day than the créneau', () => {
    const c1 = creneau({ id: 1, date: '2026-08-01' });
    const s1 = stand({
      id: 's1',
      effectifMin: 2,
      indisponibilites: [{ id: null, date: '2026-08-09', heureDebut: '09:00', heureFin: '12:00', motif: null }]
    });
    const summary = computeStaffingSummary([s1], [c1]);
    expect(summary.parCreneau[0]).toMatchObject({ standsOuverts: 1, total: 2 });
  });

  it('excludes a stand with an opening that day but no overlap with the créneau (closed-by-default)', () => {
    const c1 = creneau({ id: 1, date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00' });
    const s1 = stand({ id: 's1', effectifMin: 2 });
    const s2 = stand({
      id: 's2',
      effectifMin: 5,
      ouvertures: [{ id: null, date: c1.date, heureDebut: '20:00', heureFin: '23:00', motif: null }]
    });
    const summary = computeStaffingSummary([s1, s2], [c1]);
    expect(summary.parCreneau[0]).toMatchObject({ standsOuverts: 1, total: 2 });
  });

  it('still counts a stand whose opening overlaps the créneau, even partially', () => {
    const c1 = creneau({ id: 1, date: '2026-08-01', heureDebut: '09:00', heureFin: '14:00' });
    const s1 = stand({
      id: 's1',
      effectifMin: 2,
      ouvertures: [ouvertureIntegrale(c1)]
    });
    const summary = computeStaffingSummary([s1], [c1]);
    expect(summary.parCreneau[0]).toMatchObject({ standsOuverts: 1, total: 2 });
  });

  it('ignores an opening dated on a different day than the créneau — the stand stays open by default', () => {
    const c1 = creneau({ id: 1, date: '2026-08-01' });
    const s1 = stand({
      id: 's1',
      effectifMin: 2,
      ouvertures: [{ id: null, date: '2026-08-09', heureDebut: '09:00', heureFin: '12:00', motif: null }]
    });
    const summary = computeStaffingSummary([s1], [c1]);
    expect(summary.parCreneau[0]).toMatchObject({ standsOuverts: 1, total: 2 });
  });

  it('returns zeros and no critical créneau when there is nothing to plan', () => {
    const summary = computeStaffingSummary([], []);
    expect(summary).toMatchObject({ peakTotal: 0, workloadTotal: 0, minimumTotal: 0, creneauCritique: null });
  });

  it('sorts the per-créneau rows by date then start time', () => {
    const summary = computeStaffingSummary(
      [],
      [
        creneau({ id: 3, date: '2026-08-02', heureDebut: '09:00' }),
        creneau({ id: 1, date: '2026-08-01', heureDebut: '09:00' }),
        creneau({ id: 2, date: '2026-08-01', heureDebut: '14:00' })
      ]
    );
    expect(summary.parCreneau.map((row) => row.creneauId)).toEqual([1, 2, 3]);
  });

  it('computes duration across a midnight-crossing slot like the backend does', () => {
    const summary = computeStaffingSummary([], [creneau({ id: 1, heureDebut: '20:00', heureFin: '00:00' })]);
    expect(summary.parCreneau[0].dureeHeures).toBe(4);
  });
});

describe('computeStaffingSummary — peak vs workload bound', () => {
  it('picks the busiest créneau as the peak bound, not the sum across créneaux', () => {
    const stands = [stand({ id: 's1', effectifMin: 2 })];
    const summary = computeStaffingSummary(stands, [
      creneau({ id: 1, date: '2026-08-01' }),
      creneau({ id: 2, date: '2026-08-02' })
    ]);
    expect(summary.peakTotal).toBe(2);
  });

  it('lets the workload bound win when a single créneau would force everyone to work every slot', () => {
    // 10 seats needed on every one of 14 four-hour créneaux across 2 ISO weeks
    // (14-15 Feb 2027, a Mon/Tue pair, both in week 2027-W07): peak alone says
    // 10, but covering 14 * 4h = 56 person-hours per animateur-slot needs more
    // than 10 people once the 48h/week cap is enforced.
    const stands = [stand({ id: 's1', effectifMin: 10 })];
    const creneaux: Creneau[] = Array.from({ length: 14 }, (_, i) =>
      creneau({
        id: i,
        date: i % 2 === 0 ? '2027-02-15' : '2027-02-16',
        heureDebut: '08:00',
        heureFin: '12:00'
      })
    );
    const summary = computeStaffingSummary(stands, creneaux, 48 * 60);
    expect(summary.peakTotal).toBe(10);
    expect(summary.totalDemandeHeures).toBe(10 * 4 * 14);
    expect(summary.nombreSemaines).toBe(1);
    expect(summary.workloadTotal).toBeGreaterThan(summary.peakTotal);
    expect(summary.minimumTotal).toBe(summary.workloadTotal);
    expect(summary.bindingBound).toBe('workload');
  });

  it('falls back to the 48h/week legal default when no legal parameter is supplied', () => {
    const stands = [stand({ id: 's1', effectifMin: 5 })];
    const withDefault = computeStaffingSummary(stands, [creneau({ id: 1 })]);
    const withExplicit48h = computeStaffingSummary(stands, [creneau({ id: 1 })], 48 * 60);
    expect(withDefault.workloadTotal).toBe(withExplicit48h.workloadTotal);
  });

  it('splits the retained minimum majeurs/mineurs using the aggregate majeurs share', () => {
    const stands = [stand({ id: 's1', effectifMin: 10, reserveMajeurs: true }), stand({ id: 's2', effectifMin: 10 })];
    const summary = computeStaffingSummary(stands, [creneau({ id: 1 })]);
    // s1: 10 majeurs, 0 mineurs; s2: 5 majeurs, 5 mineurs -> 15/20 = 75% majeurs.
    expect(summary.minimumMajeurs + summary.minimumMineurs).toBe(summary.minimumTotal);
    expect(summary.minimumMajeurs).toBe(Math.ceil(summary.minimumTotal * 0.75));
  });
});
