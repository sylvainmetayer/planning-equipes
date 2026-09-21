// The legal-parameters card of the Paramètres page: one record loaded whole
// and sent back whole, so a save from this card never resets a field it did
// not show.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { ParametresLegauxCard } from './parametres-legaux';

/** Reaches the protected members the template binds to. */
type CardInternals = {
  heureDebutSoiree: { (): string; set: (value: string) => void };
  dureePauseMinutes: { (): number | null; set: (value: number) => void };
  reposQuotidienHeures: () => number | null;
  parametresError: () => string;
  parametresLoading: () => boolean;
  saveParametresLegaux: () => Promise<void>;
};

describe('ParametresLegauxCard', () => {
  const constraintsApi = {
    legalParameters: vi.fn(),
    saveLegalParameters: vi.fn(),
  };

  beforeEach(() => {
    constraintsApi.legalParameters.mockReset();
    constraintsApi.saveLegalParameters.mockReset();
    constraintsApi.saveLegalParameters.mockImplementation(async (body: unknown) => body);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ConstraintsApi, useValue: constraintsApi },
      ],
    });
  });

  function createCard(): CardInternals {
    return TestBed.createComponent(ParametresLegauxCard)
      .componentInstance as unknown as CardInternals;
  }

  it('loads every field and sends them all back, so a save never resets one it did not show', async () => {
    constraintsApi.legalParameters.mockResolvedValue({
      dureeHebdomadaireMaxMinutes: 48 * 60,
      dureeHebdomadaireMaxMineurMinutes: 35 * 60,
      reposQuotidienMinimalMinutes: 9 * 60,
      dureePauseMinutes: 30,
      coupureRepasMinutes: 60,
      coupureRepasMidiDebut: '12:00:00',
      coupureRepasMidiFin: '14:00:00',
      coupureRepasSoirDebut: '19:00:00',
      coupureRepasSoirFin: '21:00:00',
      heureDebutSoiree: '20:00:00',
      dureeVacationMaxMinutes: 6 * 60,
    });
    const card = createCard();
    await vi.waitFor(() => expect(card.dureePauseMinutes()).toBe(30));
    expect(card.reposQuotidienHeures()).toBe(9);

    card.dureePauseMinutes.set(45);
    await card.saveParametresLegaux();

    expect(constraintsApi.saveLegalParameters).toHaveBeenCalledWith({
      dureeHebdomadaireMaxMinutes: 48 * 60,
      dureeHebdomadaireMaxMineurMinutes: 35 * 60,
      reposQuotidienMinimalMinutes: 9 * 60,
      dureePauseMinutes: 45,
      coupureRepasMinutes: 60,
      coupureRepasMidiDebut: '12:00:00',
      coupureRepasMidiFin: '14:00:00',
      coupureRepasSoirDebut: '19:00:00',
      coupureRepasSoirFin: '21:00:00',
      heureDebutSoiree: '20:00:00',
      dureeVacationMaxMinutes: 6 * 60,
    });
  });

  it('sends the evening hour it was given back, and refuses to save without one', async () => {
    constraintsApi.legalParameters.mockResolvedValue({
      dureeHebdomadaireMaxMinutes: 48 * 60,
      dureeHebdomadaireMaxMineurMinutes: 35 * 60,
      reposQuotidienMinimalMinutes: 11 * 60,
      dureePauseMinutes: 30,
      coupureRepasMinutes: 60,
      coupureRepasMidiDebut: '12:00:00',
      coupureRepasMidiFin: '14:00:00',
      coupureRepasSoirDebut: '19:00:00',
      coupureRepasSoirFin: '21:00:00',
      heureDebutSoiree: '20:00:00',
      dureeVacationMaxMinutes: 6 * 60,
    });
    const card = createCard();
    await vi.waitFor(() => expect(card.heureDebutSoiree()).toBe('20:00:00'));

    card.heureDebutSoiree.set('22:00');
    await card.saveParametresLegaux();
    expect(constraintsApi.saveLegalParameters).toHaveBeenCalledWith(
      expect.objectContaining({ heureDebutSoiree: '22:00' }),
    );

    constraintsApi.saveLegalParameters.mockClear();
    card.heureDebutSoiree.set('');
    await card.saveParametresLegaux();
    expect(constraintsApi.saveLegalParameters).not.toHaveBeenCalled();
  });

  /**
   * The floor of art. L3121-16 is mirrored here so the administrator reads a
   * sentence rather than an HTTP 400. There is no second floor for minors: the
   * server raises a shorter value for them when it reads the break (ADR 0048),
   * so twenty-five is a lawful edition and this card accepts it.
   */
  it('refuses a break under the legal floor and accepts one between the two floors', async () => {
    constraintsApi.legalParameters.mockResolvedValue({
      dureeHebdomadaireMaxMinutes: 48 * 60,
      dureeHebdomadaireMaxMineurMinutes: 35 * 60,
      reposQuotidienMinimalMinutes: 11 * 60,
      dureePauseMinutes: 30,
      coupureRepasMinutes: 60,
      coupureRepasMidiDebut: '12:00:00',
      coupureRepasMidiFin: '14:00:00',
      coupureRepasSoirDebut: '19:00:00',
      coupureRepasSoirFin: '21:00:00',
      heureDebutSoiree: '20:00:00',
      dureeVacationMaxMinutes: 6 * 60,
    });
    const card = createCard();
    await vi.waitFor(() => expect(card.dureePauseMinutes()).toBe(30));

    card.dureePauseMinutes.set(15);
    await card.saveParametresLegaux();
    expect(constraintsApi.saveLegalParameters).not.toHaveBeenCalled();
    expect(card.parametresError()).toContain('L3121-16');

    card.dureePauseMinutes.set(25);
    await card.saveParametresLegaux();
    expect(constraintsApi.saveLegalParameters).toHaveBeenCalledWith(
      expect.objectContaining({ dureePauseMinutes: 25 }),
    );
  });

  it('refuses to save while a field it holds is still unknown', async () => {
    constraintsApi.legalParameters.mockReturnValue(new Promise(() => undefined));
    const card = createCard();
    await vi.waitFor(() => expect(card.parametresLoading()).toBe(true));

    await card.saveParametresLegaux();

    expect(constraintsApi.saveLegalParameters).not.toHaveBeenCalled();
  });
});
