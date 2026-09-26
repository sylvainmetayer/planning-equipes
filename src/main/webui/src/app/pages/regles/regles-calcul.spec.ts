// « Règles › Calcul »: one form, one « Enregistrer ». `solver/solver-duration.spec.ts`
// covers the budget arithmetic; what is pinned here is that nothing is written
// before the button, that each record is written only when something of it
// changed, and that a unit switch never resets what the operator typed.

import { provideZonelessChangeDetection, signal, Signal, WritableSignal } from '@angular/core';
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
import { SolverDurationUnit } from '../solver/solver-duration';
import { ReglesCalcul } from './regles-calcul';

type Internals = {
  unit: WritableSignal<SolverDurationUnit>;
  valueDraft: WritableSignal<number>;
  secondsDraft: Signal<number>;
  plateauDraft: WritableSignal<number>;
  dirty: Signal<boolean>;
  draftError: Signal<string>;
  ceilingText: Signal<string>;
  mailDraft: WritableSignal<boolean | null>;
  soireeDraft: WritableSignal<string | null>;
  ninjaDraft: WritableSignal<string | null | undefined>;
  typologieNinjaId: Signal<string | null>;
  alerteNinjaManquant: Signal<string>;
  onUnitChange: (unit: SolverDurationUnit) => void;
  onValueDraftChange: (value: number) => void;
  resetToDefault: () => void;
  cancel: () => void;
  save: () => Promise<void>;
};

const INSTANCE: SolverBudgetBounds = {
  defaultSecondsLimit: 900,
  defaultPlateauSeconds: 300,
  maxSecondsLimit: 7200,
  maxPlateauSeconds: 7200,
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

function typologie(id: string, ninja = false): TypologieItem {
  return { id, label: id, ninja } as TypologieItem;
}

describe('ReglesCalcul', () => {
  const duree = signal<number | null>(180);
  const plateau = signal<number | null>(null);
  const mail = signal(false);
  const solverSettings = {
    refresh: vi.fn(async () => undefined),
    setSettings: vi.fn(),
    bounds: signal<SolverBudgetBounds | null>(INSTANCE),
    dureeResolutionSecondes: duree,
    plateauSecondes: plateau,
    mailFinResolution: mail,
    secondsLimit: () => duree() ?? INSTANCE.defaultSecondsLimit,
    effectivePlateauSeconds: () => plateau() ?? INSTANCE.defaultPlateauSeconds,
  };
  let constraintsApi: {
    legalParameters: ReturnType<typeof vi.fn>;
    saveLegalParameters: ReturnType<typeof vi.fn>;
  };
  let crud: { save: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    duree.set(180);
    plateau.set(null);
    mail.set(false);
    solverSettings.setSettings.mockReset();
    solverSettings.setSettings.mockImplementation(
      async (seconds: number | null, plateauSeconds: number | null, mailFin: boolean) => {
        duree.set(seconds);
        plateau.set(plateauSeconds);
        mail.set(mailFin);
      },
    );
    constraintsApi = {
      legalParameters: vi.fn(async () => ({ ...LEGAUX })),
      saveLegalParameters: vi.fn(async (legaux: typeof LEGAUX) => legaux),
    };
    crud = { save: vi.fn(async () => true) };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: SolverSettingsService, useValue: solverSettings },
        { provide: ConstraintsApi, useValue: constraintsApi },
        {
          provide: AdminApi,
          useValue: { mailConfig: vi.fn(async () => ({ adminEmail: 'admin@exemple.test' })) },
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

  async function create(typologies: TypologieItem[] = []): Promise<Internals> {
    seedStore(TestBed.inject(ReferenceDataStore), 'typologies', typologies);
    const fixture = TestBed.createComponent(ReglesCalcul);
    await fixture.whenStable();
    return fixture.componentInstance as unknown as Internals;
  }

  it('reads the budget in the largest unit that keeps it whole, and writes nothing', async () => {
    const calcul = await create();

    expect(calcul.unit()).toBe('MINUTES');
    expect(calcul.valueDraft()).toBe(3);
    expect(calcul.plateauDraft()).toBe(5);
    expect(calcul.dirty()).toBe(false);
    expect(calcul.ceilingText()).toContain('2 h');
  });

  it('re-expresses the draft when the unit changes, rather than resetting it', async () => {
    const calcul = await create();

    calcul.onUnitChange('SECONDES');

    expect(calcul.valueDraft()).toBe(180);
    expect(calcul.dirty()).toBe(false);
  });

  it('writes the budget on « Enregistrer » only, the untouched plateau left to the instance', async () => {
    const calcul = await create();

    calcul.onValueDraftChange(5);
    expect(calcul.dirty()).toBe(true);
    expect(solverSettings.setSettings).not.toHaveBeenCalled();

    await calcul.save();

    expect(solverSettings.setSettings).toHaveBeenCalledExactlyOnceWith(300, null, false);
    expect(constraintsApi.saveLegalParameters).not.toHaveBeenCalled();
    expect(crud.save).not.toHaveBeenCalled();
    expect(calcul.dirty()).toBe(false);
  });

  it('says the ceiling before the server does', async () => {
    const calcul = await create();

    calcul.onUnitChange('HEURES');
    calcul.onValueDraftChange(3);

    expect(calcul.draftError()).toContain('2 h');
  });

  it('« Revenir au défaut » saves nothing by itself, then sends both halves back to the instance', async () => {
    const calcul = await create();

    calcul.resetToDefault();
    expect(solverSettings.setSettings).not.toHaveBeenCalled();
    expect(calcul.dirty()).toBe(true);

    await calcul.save();

    expect(solverSettings.setSettings).toHaveBeenCalledExactlyOnceWith(null, null, false);
  });

  it('writes the end-of-solve mail with the budget it shares a payload with', async () => {
    const calcul = await create();

    calcul.mailDraft.set(true);
    await calcul.save();

    expect(solverSettings.setSettings).toHaveBeenCalledExactlyOnceWith(180, null, true);
  });

  /** The legal record is re-read before the write: « Légal » may have stored a field meanwhile. */
  it('writes the evening over a fresh read of the legal parameters', async () => {
    const calcul = await create();
    constraintsApi.legalParameters.mockResolvedValue({ ...LEGAUX, dureePauseMinutes: 45 });

    calcul.soireeDraft.set('19:30');
    await calcul.save();

    expect(constraintsApi.saveLegalParameters).toHaveBeenCalledExactlyOnceWith({
      ...LEGAUX,
      dureePauseMinutes: 45,
      heureDebutSoiree: '19:30',
    });
    expect(solverSettings.setSettings).not.toHaveBeenCalled();
  });

  it('promotes the chosen ninja typologie, letting the server demote the previous one', async () => {
    const calcul = await create([typologie('JEUX', true), typologie('CREATIF')]);
    expect(calcul.typologieNinjaId()).toBe('JEUX');

    calcul.ninjaDraft.set('CREATIF');
    await calcul.save();

    expect(crud.save).toHaveBeenCalledOnce();
    expect(crud.save.mock.calls[0][1]).toMatchObject({ id: 'CREATIF', ninja: true });
  });

  it('clears the flag on the current holder when « Aucune » is picked, and warns', async () => {
    const calcul = await create([typologie('JEUX', true)]);

    calcul.ninjaDraft.set(null);
    expect(calcul.alerteNinjaManquant()).toContain('ninja');
    await calcul.save();

    expect(crud.save.mock.calls[0][1]).toMatchObject({ id: 'JEUX', ninja: false });
  });

  it('puts every field back on « Annuler les modifications »', async () => {
    const calcul = await create([typologie('JEUX', true)]);
    calcul.onValueDraftChange(10);
    calcul.soireeDraft.set('18:00');
    calcul.ninjaDraft.set(null);

    calcul.cancel();

    expect(calcul.dirty()).toBe(false);
    expect(calcul.valueDraft()).toBe(3);
  });
});
