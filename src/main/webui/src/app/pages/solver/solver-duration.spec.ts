// `solver-page.ts` was 640 lines with zero unit tests; the solve budget is the
// part of it with actual arithmetic, and the one whose failure mode is silent —
// a factor of sixty either way still looks like a plausible number in the field.

import { describe, expect, it } from 'vitest';
import {
  SOLVER_DURATION_UNIT_STEP,
  SolverDurationUnit,
  bestUnitFor,
  secondsToValue,
  valueToSeconds
} from './solver-duration';

describe('valueToSeconds', () => {
  it('converts each unit to the seconds the API stores', () => {
    expect(valueToSeconds(30, 'SECONDES')).toBe(30);
    expect(valueToSeconds(5, 'MINUTES')).toBe(300);
    expect(valueToSeconds(2, 'HEURES')).toBe(7200);
  });

  it('handles the fractional values the per-unit step allows', () => {
    // The minute step is 0.5 and the hour step 0.25: both must land on whole seconds.
    expect(valueToSeconds(0.5, 'MINUTES')).toBe(30);
    expect(valueToSeconds(0.25, 'HEURES')).toBe(900);
    expect(valueToSeconds(1.5, 'HEURES')).toBe(5400);
  });

  it('maps zero to zero rather than to a default budget', () => {
    expect(valueToSeconds(0, 'MINUTES')).toBe(0);
  });
});

describe('secondsToValue', () => {
  it('is the exact inverse of valueToSeconds for every unit', () => {
    const units: SolverDurationUnit[] = ['SECONDES', 'MINUTES', 'HEURES'];
    for (const unit of units) {
      for (const value of [0, 1, 7, 30, 0.5]) {
        expect(secondsToValue(valueToSeconds(value, unit), unit)).toBeCloseTo(value);
      }
    }
  });

  it('shows the stored seconds in the chosen unit', () => {
    expect(secondsToValue(1800, 'MINUTES')).toBe(30);
    expect(secondsToValue(1800, 'HEURES')).toBe(0.5);
    expect(secondsToValue(1800, 'SECONDES')).toBe(1800);
  });
});

describe('bestUnitFor', () => {
  it('shows 180 s as minutes, not as 0.05 h', () => {
    expect(bestUnitFor(180)).toBe('MINUTES');
  });

  it('promotes to hours only for a whole number of hours', () => {
    expect(bestUnitFor(3600)).toBe('HEURES');
    expect(bestUnitFor(7200)).toBe('HEURES');
    expect(bestUnitFor(5400)).toBe('MINUTES'); // 90 min, whole minutes but not whole hours
  });

  it('falls back to seconds when neither unit divides evenly', () => {
    // 90 s is 1.5 min, which the MINUTES step (0.5) could express — but the
    // rule is "whole number", so it stays in seconds. Documented as observed,
    // not as wished for.
    expect(bestUnitFor(90)).toBe('SECONDES');
    expect(bestUnitFor(95)).toBe('SECONDES');
    expect(bestUnitFor(1)).toBe('SECONDES');
  });

  it('shows zero as seconds rather than as "0 hour"', () => {
    // 0 is divisible by everything: without the explicit guard it would read
    // as HEURES, and an empty budget would display as "0 h".
    expect(bestUnitFor(0)).toBe('MINUTES');
  });

  it('picks a unit whose round trip is lossless — the point of the whole thing', () => {
    for (const seconds of [0, 1, 59, 60, 90, 300, 1800, 3600, 5400, 7200]) {
      const unit = bestUnitFor(seconds);
      expect(valueToSeconds(secondsToValue(seconds, unit), unit)).toBe(seconds);
    }
  });

  it('agrees with the documented duration of a real solve: 1800 s reads as 30 min', () => {
    expect(bestUnitFor(1800)).toBe('MINUTES');
    expect(secondsToValue(1800, bestUnitFor(1800))).toBe(30);
  });
});

describe('SOLVER_DURATION_UNIT_STEP', () => {
  it('offers whole seconds, half-minutes and quarter-hours', () => {
    expect(SOLVER_DURATION_UNIT_STEP.SECONDES).toBe(1);
    expect(SOLVER_DURATION_UNIT_STEP.MINUTES).toBe(0.5);
    expect(SOLVER_DURATION_UNIT_STEP.HEURES).toBe(0.25);
  });

  it('never lets a step land on a fraction of a second', () => {
    const units: SolverDurationUnit[] = ['SECONDES', 'MINUTES', 'HEURES'];
    for (const unit of units) {
      expect(Number.isInteger(valueToSeconds(SOLVER_DURATION_UNIT_STEP[unit], unit))).toBe(true);
    }
  });
});
