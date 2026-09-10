import { describe, expect, it } from 'vitest';
import type { ScorePoint, ScoreTrace } from './models';
import { ScoreStreamDelta, applyScoreDelta } from './score-trace';

/**
 * The splice, on its own. `solver-job.stream.spec.ts` proves the curve reaches
 * the screen through a stream; this pins the one rule a delta protocol can get
 * wrong — appending what should have replaced — with no stream, no service and
 * no clock in the way.
 */
const point = (tempsMs: number, hard: number): ScorePoint => ({
  tempsMs,
  hard,
  medium: 0,
  soft: 0,
});

function delta(overrides: Partial<ScoreStreamDelta> = {}): ScoreStreamDelta {
  return {
    jobId: 'job-1',
    editionId: 'ed-1',
    generation: 1,
    intervalleMs: 1000,
    dureeMs: 30000,
    termine: false,
    depuis: 0,
    points: [],
    ...overrides,
  };
}

function trace(points: ScorePoint[], jobId = 'job-1'): ScoreTrace {
  const { depuis: _depuis, ...rest } = delta();
  return { ...rest, jobId, points };
}

describe('applyScoreDelta', () => {
  it('starts a series from a delta at zero', () => {
    const result = applyScoreDelta(null, delta({ points: [point(0, -9), point(1000, -4)] }));

    expect(result?.points.map((p) => p.hard)).toEqual([-9, -4]);
    expect(result?.jobId).toBe('job-1');
  });

  it('appends at the index the delta names', () => {
    const held = trace([point(0, -9), point(1000, -4)]);

    const result = applyScoreDelta(held, delta({ depuis: 2, points: [point(2000, -1)] }));

    expect(result?.points.map((p) => p.hard)).toEqual([-9, -4, -1]);
    // The series held is left as it was: a signal value is never mutated in place.
    expect(held.points).toHaveLength(2);
  });

  it('replaces the series when the server restarts at zero', () => {
    const held = trace([point(0, -9), point(1000, -4), point(2000, -1)]);

    // A decimated series: the server re-sends the whole thing, shorter.
    const result = applyScoreDelta(
      held,
      delta({ depuis: 0, points: [point(0, -9), point(2000, -1)] }),
    );

    expect(result?.points.map((p) => p.tempsMs)).toEqual([0, 2000]);
  });

  it('starts a new run from scratch even when its first delta is not at zero', () => {
    const held = trace([point(0, -9), point(1000, -4)], 'job-1');

    const result = applyScoreDelta(
      held,
      delta({ jobId: 'job-2', depuis: 2, points: [point(0, -7)] }),
    );

    expect(result?.jobId).toBe('job-2');
    expect(result?.points.map((p) => p.hard)).toEqual([-7]);
  });

  it('drops the curve when the server says it no longer has one', () => {
    const held = trace([point(0, -9)]);

    expect(applyScoreDelta(held, delta({ jobId: null }))).toBeNull();
  });

  it('carries the run flags of the delta, not of the series held', () => {
    const held = trace([point(0, -9)]);

    const result = applyScoreDelta(
      held,
      delta({ depuis: 1, points: [point(1000, 0)], termine: true, dureeMs: 1000, generation: 2 }),
    );

    expect(result).toMatchObject({ termine: true, dureeMs: 1000, generation: 2 });
  });
});
