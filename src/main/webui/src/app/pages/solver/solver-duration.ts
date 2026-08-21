// Reading and writing the solve budget, kept out of the page so it is unit
// tested without rendering — the same convention `stand-draft.ts` and
// `stand-bulk-edit.ts` follow. The API stores seconds; the operator thinks in
// minutes or hours, and the round trip between the two is exactly where an
// off-by-a-factor-of-sixty hides.

/** Unit the solver page edits the solver duration in — always converted to/from seconds for the API. */
export type SolverDurationUnit = 'SECONDES' | 'MINUTES' | 'HEURES';

export const SOLVER_DURATION_UNIT_FACTORS: Record<SolverDurationUnit, number> = {
  SECONDES: 1,
  MINUTES: 60,
  HEURES: 3600
};

/** Per-unit `<input type="number">` granularity: whole seconds, half-minutes, quarter-hours. */
export const SOLVER_DURATION_UNIT_STEP: Record<SolverDurationUnit, number> = {
  SECONDES: 1,
  MINUTES: 0.5,
  HEURES: 0.25
};

export function secondsToValue(seconds: number, unit: SolverDurationUnit): number {
  return seconds / SOLVER_DURATION_UNIT_FACTORS[unit];
}

export function valueToSeconds(value: number, unit: SolverDurationUnit): number {
  return value * SOLVER_DURATION_UNIT_FACTORS[unit];
}

/** Picks the largest unit that represents `seconds` as a whole number, so e.g. 180s shows as "3 min", not "0.05 h". */
export function bestUnitFor(seconds: number): SolverDurationUnit {
  if (seconds !== 0 && seconds % SOLVER_DURATION_UNIT_FACTORS.HEURES === 0) {
    return 'HEURES';
  }
  if (seconds % SOLVER_DURATION_UNIT_FACTORS.MINUTES === 0) {
    return 'MINUTES';
  }
  return 'SECONDES';
}
