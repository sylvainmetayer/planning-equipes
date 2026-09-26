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
  maxPlateauSeconds: 3600,
};

class FakeApi {
  get = vi.fn(async (): Promise<SolverSettingsView> => ({
    dureeResolutionSecondes: null,
    plateauSecondes: null,
    mailFinResolution: false,
    instance: INSTANCE,
  }));
  put = vi.fn(async (_url: string, body: ParametresSolveur): Promise<SolverSettingsView> => ({
    ...body,
    instance: INSTANCE,
  }));
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

  it('refresh() loads the value persisted server-side', async () => {
    api.get = vi.fn(async () => ({
      dureeResolutionSecondes: 240,
      plateauSecondes: 60,
      mailFinResolution: true,
      instance: INSTANCE,
    }));
    const service = configure(api);
    await service.refresh();
    expect(api.get).toHaveBeenCalledWith('/api/parametres-solveur');
    expect(service.secondsLimit()).toBe(240);
    expect(service.effectivePlateauSeconds()).toBe(60);
    expect(service.mailFinResolution()).toBe(true);
  });

  it('an edition that set nothing reads the instance defaults', async () => {
    const service = configure(api);
    await service.refresh();
    expect(service.dureeResolutionSecondes()).toBeNull();
    expect(service.secondsLimit()).toBe(900);
    expect(service.effectivePlateauSeconds()).toBe(300);
    expect(service.bounds()).toEqual(INSTANCE);
  });

  it('refresh() propagates a fetch failure to the caller', async () => {
    api.get = vi.fn(async () => {
      throw new Error('boom');
    });
    const service = configure(api);
    await expect(service.refresh()).rejects.toThrow('boom');
  });

  it('setSettings() rounds, saves via PUT, and updates the signals', async () => {
    const service = configure(api);
    await service.setSettings(90.4, 30.2, true);
    expect(api.put).toHaveBeenCalledWith('/api/parametres-solveur', {
      dureeResolutionSecondes: 90,
      plateauSecondes: 30,
      mailFinResolution: true,
    });
    expect(service.secondsLimit()).toBe(90);
    expect(service.plateauSecondes()).toBe(30);
    expect(service.mailFinResolution()).toBe(true);
  });

  it('setSettings(null, null, …) goes back to the instance default', async () => {
    const service = configure(api);
    await service.setSettings(1200, 60, false);
    await service.setSettings(null, null, false);
    expect(api.put).toHaveBeenLastCalledWith('/api/parametres-solveur', {
      dureeResolutionSecondes: null,
      plateauSecondes: null,
      mailFinResolution: false,
    });
    expect(service.secondsLimit()).toBe(900);
  });
});
