// The plan read by typologie of jeu (issue #590): loaded on demand, and
// explicit about an edition whose plan holds nothing.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { LigneTypologie } from '../../core/models';
import { TypologiesAffectation } from './typologies-affectation';

function ligne(overrides: Partial<LigneTypologie> = {}): LigneTypologie {
  return {
    typologie: 'STRATEGIE',
    label: 'Stratégie',
    ninja: false,
    maxCreneauxParAnimateur: null,
    animateursAffectes: ['Ada Martin'],
    animateursCompetents: ['Ada Martin'],
    competentsJamaisAffectes: [],
    affectesSansCompetence: [],
    heures: 8,
    postes: 2,
    ...overrides,
  };
}

type CardInternals = {
  rapport: () => LigneTypologie[] | null;
  lignes: () => LigneTypologie[];
  aucuneAffectation: () => boolean;
  charger: () => Promise<void>;
  exporter: () => Promise<void>;
  output: () => string;
};

describe('TypologiesAffectation', () => {
  const planningApi = {
    typologiesReport: vi.fn(),
    exportTypologies: vi.fn(),
  };

  beforeEach(() => {
    planningApi.typologiesReport.mockReset();
    planningApi.exportTypologies.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanningApi, useValue: planningApi },
      ],
    });
  });

  function createCard(): TypologiesAffectation & CardInternals {
    return TestBed.createComponent(TypologiesAffectation)
      .componentInstance as TypologiesAffectation & CardInternals;
  }

  it('reads nothing until it is asked to', () => {
    const card = createCard();

    expect(card.rapport()).toBeNull();
    expect(planningApi.typologiesReport).not.toHaveBeenCalled();
  });

  it('loads every typologie and hands one to whoever asks for it by id', async () => {
    planningApi.typologiesReport.mockResolvedValue({
      typologies: [
        ligne(),
        ligne({ typologie: 'AMBIANCE', label: 'Ambiance', postes: 0, heures: 0 }),
      ],
    });
    const card = createCard();

    await card.charger();

    expect(card.lignes()).toHaveLength(2);
    expect(card.ligne('AMBIANCE')?.label).toBe('Ambiance');
    expect(card.ligne('INCONNUE')).toBeNull();
  });

  /** An edition whose plan holds nothing is a legitimate state, said in words. */
  it('says when nothing is assigned at all', async () => {
    planningApi.typologiesReport.mockResolvedValue({
      typologies: [ligne({ postes: 0, heures: 0, animateursAffectes: [] })],
    });
    const card = createCard();

    await card.charger();

    expect(card.aucuneAffectation()).toBe(true);
  });

  it('narrows to one typologie when asked, for the detail view', async () => {
    planningApi.typologiesReport.mockResolvedValue({
      typologies: [ligne(), ligne({ typologie: 'AMBIANCE', label: 'Ambiance' })],
    });
    const card = createCard();
    await card.charger();

    card.typologieId.set('AMBIANCE');

    expect(card.lignes().map((row) => row.typologie)).toEqual(['AMBIANCE']);
  });

  it('reports a failed read instead of leaving the card silent', async () => {
    planningApi.typologiesReport.mockRejectedValue(new Error('boom'));
    const card = createCard();

    await card.charger();

    expect(card.output()).not.toBe('');
    expect(card.rapport()).toBeNull();
  });
});
