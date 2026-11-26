import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ValidationsApi } from './api/validations-api';
import { DayStateView, ProgressionValidations } from './models';
import { ValidationsStore } from './validations.store';

function injectStore(): ValidationsStore {
  return TestBed.inject(ValidationsStore);
}

// What the progress banner says, and what it stays silent about. The figure
// itself is the server's; what is tested here is the wording rule — an edition
// still being typed in must never read « 0 sur 0 ».
describe('ValidationsStore', () => {
  const progression = (partial: Partial<ProgressionValidations>): ProgressionValidations => ({
    journees: 0,
    journeesValidees: 0,
    joursValides: [],
    ...partial,
  });

  let api: {
    progression: ReturnType<typeof vi.fn>;
    etats: ReturnType<typeof vi.fn>;
    setEtat: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      progression: vi.fn().mockResolvedValue(progression({})),
      etats: vi.fn().mockResolvedValue([]),
      setEtat: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ValidationsApi, useValue: api }],
    });
  });

  it('reste muet tant que rien n’a été lu', () => {
    expect(injectStore().libelle()).toBe('');
    expect(injectStore().pourcentage()).toBeNull();
  });

  it('ne dit rien sur une édition sans créneau : « 0 sur 0 » n’est pas une information', async () => {
    const instance = injectStore();

    await instance.reload();

    expect(instance.libelle()).toBe('');
    expect(instance.pourcentage()).toBeNull();
  });

  it('annonce les journées relues et la jauge qui va avec', async () => {
    api.progression.mockResolvedValue(
      progression({ journees: 12, journeesValidees: 3, joursValides: ['2026-07-08'] }),
    );
    const instance = injectStore();

    await instance.reload();

    expect(instance.libelle()).toContain('3');
    expect(instance.libelle()).toContain('12');
    expect(instance.pourcentage()).toBe(25);
    expect(instance.acceptedDays().has('2026-07-08')).toBe(true);
  });

  it('relit après un changement d’état : la bannière ne doit pas retarder', async () => {
    const dayState: DayStateView = { jour: '2026-07-08', etat: 'FIGEE' };
    api.setEtat.mockResolvedValue(dayState);
    const instance = injectStore();

    await instance.setEtat('2026-07-08', 'FIGEE', null);

    expect(api.setEtat).toHaveBeenCalledWith('2026-07-08', 'FIGEE', null);
    expect(api.progression).toHaveBeenCalled();
    expect(api.etats).toHaveBeenCalled();
  });

  it('garde le message d’erreur quand la lecture échoue', async () => {
    const instance = injectStore();
    api.progression.mockRejectedValue(new Error('serveur indisponible'));

    await instance.reload();

    expect(instance.error()).toContain('serveur indisponible');
  });
});
