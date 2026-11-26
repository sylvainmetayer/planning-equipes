// « Règles › Calcul »: one form, one « Enregistrer ». The instance's solve
// budget is shown and never written; what is pinned here is that nothing is
// written before the button, and that each record is written only when
// something of it changed.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { EditionStore } from '../../core/edition.store';
import { SolverBudgetBounds, TypologieItem } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { seedStore } from '../../core/testing/seed-store';
import { ReglesCalcul } from './regles-calcul';

const INSTANCE: SolverBudgetBounds = {
  defaultSecondsLimit: 900,
  defaultPlateauSeconds: 300,
  maxSecondsLimit: 7200,
};

const LEGAUX = {
  dureeHebdomadaireMaxMinutes: 2880,
  dureeHebdomadaireMaxMineurMinutes: 2100,
  reposQuotidienMinimalMinutes: 660,
  dureePauseMinutes: 30,
  coupureRepasMinutes: 60,
  coupureRepasMidiDebut: '12:00:00',
  coupureRepasMidiFin: '14:00:00',
  coupureRepasSoirDebut: '19:00:00',
  coupureRepasSoirFin: '21:00:00',
  heureDebutSoiree: '20:00:00',
  dureeVacationMaxMinutes: 360,
};

function typologie(id: string, polyvalent = false): TypologieItem {
  return { id, label: id, polyvalent } as TypologieItem;
}

async function create(typologies: TypologieItem[] = []): Promise<ReglesCalcul> {
  seedStore(TestBed.inject(ReferenceDataStore), 'typologies', typologies);
  const fixture = TestBed.createComponent(ReglesCalcul);
  await fixture.whenStable();
  return fixture.componentInstance;
}

describe('ReglesCalcul', () => {
  const mail = signal(false);
  const solverSettings = {
    refresh: vi.fn(() => Promise.resolve(undefined)),
    setMailFinResolution: vi.fn(),
    bounds: signal<SolverBudgetBounds | null>(INSTANCE),
    mailFinResolution: mail,
  };
  let constraintsApi: {
    legalParameters: ReturnType<typeof vi.fn>;
    saveLegalParameters: ReturnType<typeof vi.fn>;
  };
  let crud: { save: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    mail.set(false);
    solverSettings.setMailFinResolution.mockReset();
    solverSettings.setMailFinResolution.mockImplementation((mailFin: boolean) => {
      mail.set(mailFin);
      return Promise.resolve();
    });
    constraintsApi = {
      legalParameters: vi.fn(() => Promise.resolve({ ...LEGAUX })),
      saveLegalParameters: vi.fn((legaux: typeof LEGAUX) => Promise.resolve(legaux)),
    };
    crud = { save: vi.fn(() => Promise.resolve(true)) };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(() => Promise.resolve([])) } },
        { provide: SolverSettingsService, useValue: solverSettings },
        { provide: ConstraintsApi, useValue: constraintsApi },
        {
          provide: AdminApi,
          useValue: {
            mailConfig: vi.fn(() => Promise.resolve({ adminEmail: 'admin@exemple.test' })),
          },
        },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: SolverJobService,
          useValue: { activeJob: signal(null), editingLocked: () => false },
        },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'E1' }) } },
      ],
    });
  });

  it('shows the instance budget and writes nothing', async () => {
    const calcul = await create();

    expect(calcul['budgetText']()).toContain('15 min');
    expect(calcul['budgetText']()).toContain('5 min');
    expect(calcul.dirty()).toBe(false);
  });

  it('writes the end-of-solve mail on « Enregistrer » only', async () => {
    const calcul = await create();

    calcul['mailDraft'].set(true);
    expect(calcul.dirty()).toBe(true);
    expect(solverSettings.setMailFinResolution).not.toHaveBeenCalled();
    await calcul['save']();

    expect(solverSettings.setMailFinResolution).toHaveBeenCalledExactlyOnceWith(true);
    expect(constraintsApi.saveLegalParameters).not.toHaveBeenCalled();
    expect(crud.save).not.toHaveBeenCalled();
    expect(calcul.dirty()).toBe(false);
  });

  /** The legal record is re-read before the write: « Légal » may have stored a field meanwhile. */
  it('writes the evening over a fresh read of the legal parameters', async () => {
    const calcul = await create();
    constraintsApi.legalParameters.mockResolvedValue({ ...LEGAUX, dureePauseMinutes: 45 });

    calcul['soireeDraft'].set('19:30');
    await calcul['save']();

    expect(constraintsApi.saveLegalParameters).toHaveBeenCalledExactlyOnceWith({
      ...LEGAUX,
      dureePauseMinutes: 45,
      heureDebutSoiree: '19:30',
    });
    expect(solverSettings.setMailFinResolution).not.toHaveBeenCalled();
  });

  it('promotes the chosen polyvalent typologie, letting the server demote the previous one', async () => {
    const calcul = await create([typologie('JEUX', true), typologie('CREATIF')]);
    expect(calcul['typologiePolyvalentId']()).toBe('JEUX');

    calcul['polyvalentDraft'].set('CREATIF');
    await calcul['save']();

    expect(crud.save).toHaveBeenCalledOnce();
    expect(crud.save.mock.calls[0][1]).toMatchObject({ id: 'CREATIF', polyvalent: true });
  });

  it('clears the flag on the current holder when « Aucune » is picked, and warns', async () => {
    const calcul = await create([typologie('JEUX', true)]);

    calcul['polyvalentDraft'].set(null);
    expect(calcul['alertePolyvalentManquant']()).toContain('polyvalent');
    await calcul['save']();

    expect(crud.save.mock.calls[0][1]).toMatchObject({ id: 'JEUX', polyvalent: false });
  });

  it('puts every field back on « Annuler les modifications »', async () => {
    const calcul = await create([typologie('JEUX', true)]);
    calcul['mailDraft'].set(true);
    calcul['soireeDraft'].set('18:00');
    calcul['polyvalentDraft'].set(null);

    calcul.cancel();

    expect(calcul.dirty()).toBe(false);
    expect(calcul['mailFinResolution']()).toBe(false);
  });
});
