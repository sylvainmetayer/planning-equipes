import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_SOLVER_SECONDS_LIMIT, SolverSettingsService } from './solver-settings.service';

function configure(): SolverSettingsService {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), SolverSettingsService] });
  return TestBed.inject(SolverSettingsService);
}

describe('SolverSettingsService', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('defaults to 180 seconds (3 min)', () => {
    const service = configure();
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
  });

  it('setSecondsLimit() rounds and updates the signal', () => {
    const service = configure();
    service.setSecondsLimit(90.4);
    expect(service.secondsLimit()).toBe(90);
  });

  it('setSecondsLimit() with an invalid value falls back to the default', () => {
    const service = configure();
    service.setSecondsLimit(-5);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
    service.setSecondsLimit(NaN);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
  });

  it('persists across service instances via localStorage', () => {
    const first = configure();
    first.setSecondsLimit(240);

    TestBed.resetTestingModule();
    const second = configure();
    expect(second.secondsLimit()).toBe(240);
  });
});
