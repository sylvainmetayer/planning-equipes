import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { ParametresSolveur, SolverSettingsView } from './models';
import { DEFAULT_SOLVER_SECONDS_LIMIT, SolverSettingsService } from './solver-settings.service';

const INSTANCE = {
  defaultSecondsLimit: 900,
  defaultPlateauSeconds: 300,
  maxSecondsLimit: 3600,
};

class FakeApi {
  get = vi.fn((): Promise<SolverSettingsView> =>
    Promise.resolve({ mailFinResolution: false, instance: INSTANCE }),
  );
  put = vi.fn((_url: string, body: ParametresSolveur): Promise<SolverSettingsView> =>
    Promise.resolve({ ...body, instance: INSTANCE }),
  );
}

function configure(api: FakeApi): SolverSettingsService {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      SolverSettingsService,
      { provide: ApiService, useValue: api },
    ],
  });
  return TestBed.inject(SolverSettingsService);
}

describe('SolverSettingsService', () => {
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
  });

  it('defaults to 900 seconds (15 min) before the initial fetch resolves', () => {
    const service = configure(api);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
  });

  it('refresh() reads the instance budget and the edition mail switch', async () => {
    api.get = vi.fn(() =>
      Promise.resolve({
        mailFinResolution: true,
        instance: { ...INSTANCE, defaultSecondsLimit: 240, defaultPlateauSeconds: 60 },
      }),
    );
    const service = configure(api);
    await service.refresh();
    expect(api.get).toHaveBeenCalledWith('/api/parametres/calcul');
    expect(service.secondsLimit()).toBe(240);
    expect(service.effectivePlateauSeconds()).toBe(60);
    expect(service.mailFinResolution()).toBe(true);
  });

  it('refresh() propagates a fetch failure to the caller', async () => {
    api.get = vi.fn(() => Promise.reject(new Error('boom')));
    const service = configure(api);
    await expect(service.refresh()).rejects.toThrow('boom');
  });

  it('setMailFinResolution() saves via PUT and updates the signal', async () => {
    const service = configure(api);
    await service.setMailFinResolution(true);
    expect(api.put).toHaveBeenCalledWith('/api/parametres/calcul', {
      mailFinResolution: true,
      modifieLe: null,
    });
    expect(service.mailFinResolution()).toBe(true);
    expect(service.bounds()).toEqual(INSTANCE);
  });
});
