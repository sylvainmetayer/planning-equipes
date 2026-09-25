import { describe, expect, it } from 'vitest';
import { ConstraintHistory, ResolutionUnderDosage, WeightChange } from '../../core/models';
import { changeLabel, historyChart, historyItems, originLabel } from './weight-history';

function change(at: string, patch: Partial<WeightChange> = {}): WeightChange {
  return {
    id: 1,
    name: 'souhaitsIncompatibles',
    weightBefore: 1,
    weightAfter: 20,
    backToDefault: false,
    activeBefore: null,
    activeAfter: null,
    origin: 'SCREEN',
    actor: 'ADMIN',
    sourceEdition: null,
    createdAt: at,
    ...patch,
  };
}

function resolution(
  at: string,
  ruleViolations: number | null,
  instance = 5,
): ResolutionUnderDosage {
  return {
    id: 1,
    createdAt: at,
    score: '0hard/-10medium/-3soft',
    scoreHard: 0,
    scoreMedium: -10,
    scoreSoft: -3,
    scoreMediumHorsPlancher: -10,
    ruleViolations,
    dosage: {
      weights: {},
      instanceWeights: { stabiliteDuPlanPublie: instance },
      disabled: [],
      enabled: [],
    },
  };
}

const HISTORY: ConstraintHistory = {
  name: 'souhaitsIncompatibles',
  changes: [change('2026-09-02T10:00:00Z')],
  resolutions: [
    resolution('2026-09-01T10:00:00Z', 12),
    resolution('2026-09-02T10:00:00Z', 4),
    resolution('2026-09-03T10:00:00Z', null, 8),
  ],
};

describe('weight history', () => {
  it('merges changes and solves by date, a change first when they tie', () => {
    const items = historyItems(HISTORY);
    expect(items.map((item) => item.kind)).toEqual([
      'resolution',
      'change',
      'resolution',
      'resolution',
    ]);
  });

  it('flags a solve under which the deployment itself changed its weights', () => {
    const items = historyItems(HISTORY);
    const flags = items.flatMap((item) =>
      item.kind === 'resolution' ? [item.instanceChanged] : [],
    );
    expect(flags).toEqual([false, false, true]);
  });

  it('words a weight, a return to the default and an activation', () => {
    expect(changeLabel(change('x'))).toBe('Poids 1 → 20');
    expect(
      changeLabel(change('x', { weightBefore: 20, weightAfter: 1, backToDefault: true })),
    ).toBe('Poids 20 → défaut (1)');
    expect(
      changeLabel(
        change('x', {
          weightBefore: null,
          weightAfter: null,
          activeBefore: true,
          activeAfter: false,
        }),
      ),
    ).toBe('active → désactivée');
  });

  it('names the origin, never a person', () => {
    expect(originLabel(change('x', { origin: 'ASSISTANT' }))).toBe('assistant');
    expect(originLabel(change('x', { origin: 'DUPLICATION', sourceEdition: 'A-2025' }))).toBe(
      'hérité de A-2025',
    );
  });

  it('draws the violations on their own scale, the changes as markers, by rank', () => {
    const chart = historyChart(historyItems(HISTORY), 100, 50);
    expect(chart).not.toBeNull();
    expect(chart!.maxViolations).toBe(12);
    expect(chart!.points.map((point) => point.violations)).toEqual([12, 4]);
    expect(chart!.markers).toHaveLength(1);
    // Rank 0 and 2 for the two measured solves, the change at rank 1 in between.
    expect(chart!.points[0].x).toBeLessThan(chart!.markers[0].x);
    expect(chart!.markers[0].x).toBeLessThan(chart!.points[1].x);
    // Twelve violations sit at the top, four lower down.
    expect(chart!.points[0].y).toBeLessThan(chart!.points[1].y);
    expect(chart!.polyline.split(' ')).toHaveLength(2);
  });

  it('draws nothing when no solve measured the rule', () => {
    expect(historyChart(historyItems({ ...HISTORY, resolutions: [] }))).toBeNull();
  });
});
