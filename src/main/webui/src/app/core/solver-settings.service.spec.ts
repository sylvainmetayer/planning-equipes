import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { DEFAULT_SOLVER_SECONDS_LIMIT, SolverSettingsService } from './solver-settings.service';

class FakeApi {
  get = vi.fn(async () => ({ dureeResolutionSecondes: DEFAULT_SOLVER_SECONDS_LIMIT }));
  put = vi.fn(async (_url: string, body: { dureeResolutionSecondes: number }) => body);
}

function configure(api: FakeApi): SolverSettingsService {
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), SolverSettingsService, { provide: ApiService, useValue: api }]
  });
  return TestBed.inject(SolverSettingsService);
}

describe('SolverSettingsService', () => {
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
  });

  it('defaults to 180 seconds (3 min) before the initial fetch resolves', () => {
    const service = configure(api);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
  });

  it('refresh() loads the value persisted server-side', async () => {
    api.get = vi.fn(async () => ({ dureeResolutionSecondes: 240 }));
    const service = configure(api);
    await service.refresh();
    expect(api.get).toHaveBeenCalledWith('/api/parametres-solveur');
    expect(service.secondsLimit()).toBe(240);
  });

  it('refresh() propagates a fetch failure to the caller', async () => {
    api.get = vi.fn(async () => {
      throw new Error('boom');
    });
    const service = configure(api);
    await expect(service.refresh()).rejects.toThrow('boom');
  });

  it('setSecondsLimit() rounds, saves via PUT, and updates the signal', async () => {
    const service = configure(api);
    await service.setSecondsLimit(90.4);
    expect(api.put).toHaveBeenCalledWith('/api/parametres-solveur', { dureeResolutionSecondes: 90 });
    expect(service.secondsLimit()).toBe(90);
  });

  it('setSecondsLimit() with an invalid value falls back to the default', async () => {
    const service = configure(api);
    await service.setSecondsLimit(-5);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
    await service.setSecondsLimit(NaN);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
  });
});
