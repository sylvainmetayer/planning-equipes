import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { ParametresSolveur } from './models';
import { DEFAULT_SOLVER_SECONDS_LIMIT, SolverSettingsService } from './solver-settings.service';

class FakeApi {
  get = vi.fn(async () => ({
    dureeResolutionSecondes: DEFAULT_SOLVER_SECONDS_LIMIT,
    mailFinResolution: false,
  }));
  put = vi.fn(async (_url: string, body: ParametresSolveur) => body);
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
    api.get = vi.fn(async () => ({ dureeResolutionSecondes: 240, mailFinResolution: true }));
    const service = configure(api);
    await service.refresh();
    expect(api.get).toHaveBeenCalledWith('/api/parametres-solveur');
    expect(service.secondsLimit()).toBe(240);
    expect(service.mailFinResolution()).toBe(true);
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
    expect(api.put).toHaveBeenCalledWith('/api/parametres-solveur', {
      dureeResolutionSecondes: 90,
      mailFinResolution: false,
    });
    expect(service.secondsLimit()).toBe(90);
  });

  it('setSecondsLimit() with an invalid value falls back to the default', async () => {
    const service = configure(api);
    await service.setSecondsLimit(-5);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
    await service.setSecondsLimit(Number.NaN);
    expect(service.secondsLimit()).toBe(DEFAULT_SOLVER_SECONDS_LIMIT);
  });

  it('envoie toujours les deux réglages, sinon écrire l’un effacerait l’autre', async () => {
    // Le serveur persiste un objet entier : un PUT qui n'emporterait qu'un
    // champ remettrait silencieusement l'autre à sa valeur par défaut.
    api.get = vi.fn(async () => ({ dureeResolutionSecondes: 600, mailFinResolution: true }));
    const service = configure(api);
    await service.refresh();

    await service.setSecondsLimit(300);
    expect(api.put).toHaveBeenLastCalledWith('/api/parametres-solveur', {
      dureeResolutionSecondes: 300,
      mailFinResolution: true,
    });

    await service.setMailFinResolution(false);
    expect(api.put).toHaveBeenLastCalledWith('/api/parametres-solveur', {
      dureeResolutionSecondes: 300,
      mailFinResolution: false,
    });
    expect(service.secondsLimit()).toBe(300);
    expect(service.mailFinResolution()).toBe(false);
  });
});
