import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ValidationsApi } from './api/validations-api';
import { ProgressionValidations, ValidationJournee } from './models';
import { ValidationsStore } from './validations.store';

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
    list: ReturnType<typeof vi.fn>;
    accept: ReturnType<typeof vi.fn>;
    withdraw: ReturnType<typeof vi.fn>;
  };

  function store(): ValidationsStore {
    return TestBed.inject(ValidationsStore);
  }

  beforeEach(() => {
    api = {
      progression: vi.fn().mockResolvedValue(progression({})),
      list: vi.fn().mockResolvedValue([]),
      accept: vi.fn(),
      withdraw: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ValidationsApi, useValue: api }],
    });
  });

  it('reste muet tant que rien n’a été lu', () => {
    expect(store().libelle()).toBe('');
    expect(store().pourcentage()).toBeNull();
  });

  it('ne dit rien sur une édition sans créneau : « 0 sur 0 » n’est pas une information', async () => {
    const instance = store();

    await instance.reload();

    expect(instance.libelle()).toBe('');
    expect(instance.pourcentage()).toBeNull();
  });

  it('annonce les journées relues et la jauge qui va avec', async () => {
    api.progression.mockResolvedValue(
      progression({ journees: 12, journeesValidees: 3, joursValides: ['2026-07-08'] }),
    );
    const instance = store();

    await instance.reload();

    expect(instance.libelle()).toContain('3');
    expect(instance.libelle()).toContain('12');
    expect(instance.pourcentage()).toBe(25);
    expect(instance.acceptedDays().has('2026-07-08')).toBe(true);
  });

  it('relit après avoir accepté une journée : la bannière ne doit pas retarder', async () => {
    const validation: ValidationJournee = {
      id: 'V1',
      jour: '2026-07-08',
      valideLe: '2026-07-01T10:00:00Z',
      validePar: 'admin',
      commentaire: null,
    };
    api.accept.mockResolvedValue({ validation, verrouPose: false });
    const instance = store();

    await instance.accept({ jour: '2026-07-08' });

    expect(api.accept).toHaveBeenCalledWith({ jour: '2026-07-08' });
    expect(api.progression).toHaveBeenCalled();
  });

  it('relit après un retrait, et garde le message d’erreur quand la lecture échoue', async () => {
    api.withdraw.mockResolvedValue(undefined);
    const instance = store();
    await instance.withdraw('V1');
    expect(api.progression).toHaveBeenCalled();

    api.progression.mockRejectedValue(new Error('serveur indisponible'));
    await instance.reload();

    expect(instance.error()).toContain('serveur indisponible');
  });
});
