// The solve budget card. `solver-duration.spec.ts` covers the arithmetic;
// what is pinned here is the round trip with the server — read on appearance,
// written on « Enregistrer » only — and that a unit switch never resets the
// value the operator typed.

import { provideZonelessChangeDetection, Signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../../core/notification.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { SolverDurationCard } from './solver-duration-card';
import { SolverDurationUnit } from './solver-duration';

type CardInternals = {
  unit: WritableSignal<SolverDurationUnit>;
  valueDraft: WritableSignal<number>;
  secondsDraft: Signal<number>;
  dirty: Signal<boolean>;
  error: Signal<string>;
  loading: Signal<boolean>;
  saving: Signal<boolean>;
  onUnitChange: (unit: SolverDurationUnit) => void;
  onValueDraftChange: (value: number) => void;
  save: () => Promise<void>;
};

describe('SolverDurationCard', () => {
  let secondsLimit = 180;
  const solverSettings = {
    refresh: vi.fn(),
    setSecondsLimit: vi.fn(),
    secondsLimit: () => secondsLimit
  };
  const notifications = { notify: vi.fn() };
  let fixture: ComponentFixture<SolverDurationCard>;

  beforeEach(() => {
    secondsLimit = 180;
    solverSettings.refresh.mockReset();
    solverSettings.setSecondsLimit.mockReset();
    notifications.notify.mockClear();
    solverSettings.refresh.mockResolvedValue(undefined);
    solverSettings.setSecondsLimit.mockImplementation(async (seconds: number) => {
      secondsLimit = seconds;
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: SolverSettingsService, useValue: solverSettings },
        { provide: NotificationService, useValue: notifications }
      ]
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
    expect(card.dirty()).toBe(false);
  });

  it('re-expresses the draft when the unit changes, rather than resetting it', async () => {
    const card = await createCard();

    card.onUnitChange('SECONDES');

    expect(card.valueDraft()).toBe(180);
    expect(card.secondsDraft()).toBe(180);
    expect(card.dirty()).toBe(false);
  });

  it('writes nothing until « Enregistrer », then tells every browser', async () => {
    const card = await createCard();

    card.onValueDraftChange(5);
    expect(card.dirty()).toBe(true);
    expect(solverSettings.setSecondsLimit).not.toHaveBeenCalled();

    await card.save();

    expect(solverSettings.setSecondsLimit).toHaveBeenCalledExactlyOnceWith(300);
    expect(card.dirty()).toBe(false);
    expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
  });

  it('shows the refusal in place and keeps the draft', async () => {
    solverSettings.setSecondsLimit.mockRejectedValue(new Error('Durée trop courte.'));
    const card = await createCard();
    card.onValueDraftChange(0.5);

    await card.save();

    expect(card.error()).toContain('Durée trop courte.');
    expect(card.valueDraft()).toBe(0.5);
    expect(card.saving()).toBe(false);
  });

  it('says why the budget could not be read', async () => {
    solverSettings.refresh.mockRejectedValue(new Error('Serveur injoignable.'));

    const card = await createCard();

    expect(card.error()).toContain('Serveur injoignable.');
    expect(card.loading()).toBe(false);
  });
});
