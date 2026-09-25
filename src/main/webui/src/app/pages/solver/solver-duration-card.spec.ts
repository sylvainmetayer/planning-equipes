// The solve budget card. `solver-duration.spec.ts` covers the arithmetic;
// what is pinned here is the round trip with the server — read on appearance,
// written on « Enregistrer » only — that a unit switch never resets the value
// the operator typed, and that the ceiling is said before the server says it.

import { provideZonelessChangeDetection, signal, Signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionStore } from '../../core/edition.store';
import { SolverBudgetBounds } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService, TrackedJob } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { SolverDurationCard } from './solver-duration-card';
import { SolverDurationUnit } from './solver-duration';

type CardInternals = {
  unit: WritableSignal<SolverDurationUnit>;
  valueDraft: WritableSignal<number>;
  secondsDraft: Signal<number>;
  plateauDraft: WritableSignal<number>;
  dirty: Signal<boolean>;
  draftError: Signal<string>;
  ceilingText: Signal<string>;
  defaultsText: Signal<string>;
  runningText: Signal<string>;
  error: Signal<string>;
  loading: Signal<boolean>;
  saving: Signal<boolean>;
  onUnitChange: (unit: SolverDurationUnit) => void;
  onValueDraftChange: (value: number) => void;
  onPlateauDraftChange: (value: number) => void;
  save: () => Promise<void>;
  resetToDefault: () => Promise<void>;
};

const INSTANCE: SolverBudgetBounds = {
  defaultSecondsLimit: 900,
  defaultPlateauSeconds: 300,
  maxSecondsLimit: 7200,
  maxPlateauSeconds: 7200,
};

describe('SolverDurationCard', () => {
  const duree = signal<number | null>(180);
  const plateau = signal<number | null>(null);
  const solverSettings = {
    refresh: vi.fn(),
    setBudget: vi.fn(),
    bounds: signal<SolverBudgetBounds | null>(INSTANCE),
    dureeResolutionSecondes: duree,
    plateauSecondes: plateau,
    secondsLimit: () => duree() ?? INSTANCE.defaultSecondsLimit,
    effectivePlateauSeconds: () => plateau() ?? INSTANCE.defaultPlateauSeconds,
  };
  const activeJob = signal<TrackedJob | null>(null);
  const notifications = { notify: vi.fn() };
  let fixture: ComponentFixture<SolverDurationCard>;

  beforeEach(() => {
    duree.set(180);
    plateau.set(null);
    activeJob.set(null);
    solverSettings.refresh.mockReset();
    solverSettings.setBudget.mockReset();
    notifications.notify.mockClear();
    solverSettings.refresh.mockResolvedValue(undefined);
    solverSettings.setBudget.mockImplementation(
      async (seconds: number | null, plateauSeconds: number | null) => {
        duree.set(seconds);
        plateau.set(plateauSeconds);
      },
    );
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: SolverSettingsService, useValue: solverSettings },
        { provide: NotificationService, useValue: notifications },
        { provide: SolverJobService, useValue: { activeJob } },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'E1' }) } },
      ],
    });
  });

  async function createCard(): Promise<CardInternals> {
    fixture = TestBed.createComponent(SolverDurationCard);
    await fixture.whenStable();
    return fixture.componentInstance as unknown as CardInternals;
  }

  it('reads the budget when it appears, in the largest unit that keeps it whole', async () => {
    const card = await createCard();

    expect(solverSettings.refresh).toHaveBeenCalledOnce();
    expect(card.unit()).toBe('MINUTES');
    expect(card.valueDraft()).toBe(3);
    expect(card.plateauDraft()).toBe(5);
    expect(card.dirty()).toBe(false);
  });

  it('says the ceiling and the instance defaults', async () => {
    const card = await createCard();

    expect(card.ceilingText()).toContain('2 h');
    expect(card.defaultsText()).toContain('15 min');
    expect(card.defaultsText()).toContain('5 min');
  });

  it('re-expresses the draft when the unit changes, rather than resetting it', async () => {
    const card = await createCard();

    card.onUnitChange('SECONDES');

    expect(card.valueDraft()).toBe(180);
    expect(card.secondsDraft()).toBe(180);
    expect(card.dirty()).toBe(false);
  });

  it('writes nothing until « Enregistrer », and leaves an untouched default plateau to the instance', async () => {
    const card = await createCard();

    card.onValueDraftChange(5);
    expect(card.dirty()).toBe(true);
    expect(solverSettings.setBudget).not.toHaveBeenCalled();

    await card.save();

    expect(solverSettings.setBudget).toHaveBeenCalledExactlyOnceWith(300, null);
    expect(card.dirty()).toBe(false);
    expect(notifications.notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'success' }),
    );
  });

  it('refuses above the ceiling, and a plateau longer than the duration, before the server', async () => {
    const card = await createCard();

    card.onUnitChange('HEURES');
    card.onValueDraftChange(3);
    expect(card.draftError()).toContain('2 h');

    card.onValueDraftChange(0.25);
    card.onPlateauDraftChange(30);
    expect(card.draftError()).toContain('dépasser la durée');
  });

  it('holds a stored value above a ceiling lowered since against nothing but its own change', async () => {
    duree.set(10800);
    const card = await createCard();

    card.onPlateauDraftChange(10);
    expect(card.dirty()).toBe(true);
    expect(card.draftError()).toBe('');

    card.onUnitChange('HEURES');
    card.onValueDraftChange(2.5);
    expect(card.draftError()).toContain('2 h');
  });

  it('holds a duration under the default plateau only against a plateau the edition sets', async () => {
    const card = await createCard();

    card.onUnitChange('SECONDES');
    card.onValueDraftChange(120);
    expect(card.draftError()).toBe('');

    await card.save();
    expect(solverSettings.setBudget).toHaveBeenCalledExactlyOnceWith(120, null);

    card.onPlateauDraftChange(4);
    expect(card.draftError()).toContain('dépasser la durée');
  });

  it('goes back to the instance default in one click', async () => {
    plateau.set(60);
    const card = await createCard();

    await card.resetToDefault();

    expect(solverSettings.setBudget).toHaveBeenCalledExactlyOnceWith(null, null);
    expect(card.valueDraft()).toBe(15);
  });

  it('says what the solve under way was given, and why it differs', async () => {
    activeJob.set({
      id: 'j',
      type: 'SOLVE',
      label: 'Calcul',
      startedAtMs: 0,
      mine: true,
      editionId: 'E1',
      editionNom: 'Édition',
      secondsLimit: 3600,
      plateauSeconds: 0,
      cappedFromSecondsLimit: 10800,
      cappedFromPlateauSeconds: null,
    });
    const card = await createCard();

    expect(card.runningText()).toContain('1 h');
    expect(card.runningText()).toContain('jamais');
    expect(card.runningText()).toContain('La durée enregistrée (3 h)');
    expect(card.runningText()).not.toContain('plateau enregistré');
  });

  it('shows the refusal in place and keeps the draft', async () => {
    solverSettings.setBudget.mockRejectedValue(new Error('Durée trop longue.'));
    const card = await createCard();
    card.onValueDraftChange(4);

    await card.save();

    expect(card.error()).toContain('Durée trop longue.');
    expect(card.valueDraft()).toBe(4);
    expect(card.saving()).toBe(false);
  });

  it('says why the budget could not be read', async () => {
    solverSettings.refresh.mockRejectedValue(new Error('Serveur injoignable.'));

    const card = await createCard();

    expect(card.error()).toContain('Serveur injoignable.');
    expect(card.loading()).toBe(false);
  });
});
