import { describe, expect, it } from 'vitest';
import { elapsedSeconds, formatDuration, type TrackedJob } from './solver-job.service';

describe('formatDuration', () => {
  it('shows only seconds under a minute', () => {
    expect(formatDuration(45)).toBe('45s');
  });

  it('shows minutes and zero-padded seconds over a minute', () => {
    expect(formatDuration(65)).toBe('1m 05s');
    expect(formatDuration(600)).toBe('10m 00s');
  });

  it('clamps negative or invalid input to zero', () => {
    expect(formatDuration(-10)).toBe('0s');
    expect(formatDuration(Number.NaN)).toBe('0s');
  });
});

describe('elapsedSeconds', () => {
  const job: TrackedJob = {
    id: 'j1',
    type: 'SOLVE',
    label: 'Timefold solve',
    startedAtMs: 10_000,
    mine: true,
    secondsLimit: 180
  };

  it('rounds the elapsed time to whole seconds', () => {
    expect(elapsedSeconds(job, 15_400)).toBe(5);
  });

  it('never returns a negative duration', () => {
    expect(elapsedSeconds(job, 5_000)).toBe(0);
  });
});
