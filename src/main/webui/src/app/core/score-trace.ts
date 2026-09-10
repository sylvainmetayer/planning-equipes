// The score curve of a solve, as the stream delivers it (issue #304).
//
// The server pushes DELTAS, not snapshots: the series grows for as long as the
// run lasts, and re-sending it whole every second would make the traffic grow
// with the solve. A delta protocol has exactly one failure mode, and it costs
// the user a wrong curve rather than an error: appending what should have
// replaced. The splice lives here, pure, so that one rule is testable without
// a stream, a service or a clock.

import { ScorePoint, ScoreTrace } from './models';

/**
 * One `score` event: the points of the running solve's curve this connection
 * had not received yet.
 *
 * `depuis` is the index the first point holds in the series: 0 means "replace
 * what you hold" (a fresh connection, a new run, or a series the server has
 * just decimated), anything else means "append at that index". The client
 * never resets on its own, so both sides stay on the same series without a
 * handshake.
 */
export interface ScoreStreamDelta extends Omit<ScoreTrace, 'points' | 'jobId'> {
  /** Null means "there is no curve any more" — a restarted server. */
  jobId: string | null;
  depuis: number;
  points: ScorePoint[];
}

/**
 * The series after `delta`, given the one held so far. Null when the server
 * says it has no curve at all: without that the last curve received would stay
 * on screen with its last `termine: false`, reading as a live solve for a run
 * the server already reports as interrupted.
 */
export function applyScoreDelta(
  current: ScoreTrace | null,
  delta: ScoreStreamDelta,
): ScoreTrace | null {
  if (delta.jobId === null) {
    return null;
  }
  // Anything but a plain append restarts from what the server just sent: the
  // server resets its cursor for exactly the same cases (a new run, a
  // decimated series, a reconnection), so the two never disagree.
  const appended = delta.depuis > 0 && current !== null && current.jobId === delta.jobId;
  const points = appended
    ? [...current.points.slice(0, delta.depuis), ...delta.points]
    : [...delta.points];
  return {
    jobId: delta.jobId,
    editionId: delta.editionId,
    generation: delta.generation,
    intervalleMs: delta.intervalleMs,
    dureeMs: delta.dureeMs,
    termine: delta.termine,
    points,
  };
}
