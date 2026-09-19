// The quality-parameters card of the Paramètres page (issue #591): six
// thresholds loaded whole and sent back whole, and the pair of service hours
// refused half-filled.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { ParametresQualiteCard } from './parametres-qualite';

/** Reaches the protected members the template binds to. */
type CardInternals = {
  dailyLocationsCap: { (): number | null; set: (value: number | null) => void };
  typologiesDistinctesMax: { (): number | null; set: (value: number | null) => void };
  joursConsecutifsMax: { (): number | null; set: (value: number | null) => void };
  heureServiceTardif: { (): string; set: (value: string) => void };
  heureServiceMatinal: { (): string; set: (value: string) => void };
  restAfterLateServiceHours: () => number | null;
  error: () => string;
  save: () => Promise<void>;
};

describe('ParametresQualiteCard', () => {
  const constraintsApi = {
    qualityParameters: vi.fn(),
    saveQualityParameters: vi.fn(),
  };

  beforeEach(() => {
    constraintsApi.qualityParameters.mockReset();
    constraintsApi.saveQualityParameters.mockReset();
    constraintsApi.saveQualityParameters.mockImplementation(async (body: unknown) => body);
    constraintsApi.qualityParameters.mockResolvedValue({
      maxEmplacementsDistinctsParJour: 3,
      heureServiceTardif: '22:00:00',
      heureServiceMatinal: '10:00:00',
      reposSouhaiteApresServiceTardifMinutes: 720,
      typologiesDistinctesMax: 2,
      joursConsecutifsMax: 6,
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ConstraintsApi, useValue: constraintsApi },
      ],
    });
  });

  function createCard(): CardInternals {
    return TestBed.createComponent(ParametresQualiteCard)
      .componentInstance as unknown as CardInternals;
  }

  it('loads every threshold and sends them all back, so a save never resets one it did not show', async () => {
    const card = createCard();
    await Promise.resolve();
    await Promise.resolve();

    expect(card.dailyLocationsCap()).toBe(3);
    expect(card.typologiesDistinctesMax()).toBe(2);
    expect(card.joursConsecutifsMax()).toBe(6);
    // `HH:mm:ss` on the wire, `HH:mm` in the time input.
    expect(card.heureServiceTardif()).toBe('22:00');
    expect(card.restAfterLateServiceHours()).toBe(12);

    card.typologiesDistinctesMax.set(3);
    await card.save();

    expect(constraintsApi.saveQualityParameters).toHaveBeenCalledWith({
      maxEmplacementsDistinctsParJour: 3,
      typologiesDistinctesMax: 3,
      joursConsecutifsMax: 6,
      heureServiceTardif: '22:00',
      heureServiceMatinal: '10:00',
      reposSouhaiteApresServiceTardifMinutes: 720,
    });
  });

  it('refuses one service hour without the other, which describes no pair', async () => {
    const card = createCard();
    await Promise.resolve();
    await Promise.resolve();

    card.heureServiceMatinal.set('');
    await card.save();

    expect(constraintsApi.saveQualityParameters).not.toHaveBeenCalled();
    expect(card.error()).not.toBe('');
  });

  it('sends both hours as null when neither is set, which is how the rule is silenced', async () => {
    const card = createCard();
    await Promise.resolve();
    await Promise.resolve();

    card.heureServiceTardif.set('');
    card.heureServiceMatinal.set('');
    await card.save();

    expect(constraintsApi.saveQualityParameters).toHaveBeenCalledWith(
      expect.objectContaining({ heureServiceTardif: null, heureServiceMatinal: null }),
    );
  });
});
