// The Charge tab: the synthesis of « Par personne », hosted. Pinned here: its
// view state is read from the address bar and written back, and the hours are
// read on the plan the address names — the reports themselves are stubbed.

import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { Animateur, HoursReading, HoursSource, PosteAffectation } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ChargeTab } from './charge-tab';

const ALICE: Animateur = {
  id: 'alice',
  prenom: 'Alice',
  nom: 'Martin',
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: [],
};

const POSTE: PosteAffectation = {
  id: 'p1',
  stand: {
    id: 'S1',
    nom: 'Tir',
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  },
  creneau: { id: 1, jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00' },
  animateur: ALICE,
};

describe('ChargeTab', () => {
  let fixture: ComponentFixture<ChargeTab>;
  const hoursReport = vi.fn((source: HoursSource | null = null): Promise<HoursReading> =>
    Promise.resolve({
      source: source ?? 'persiste',
      planDate: null,
      publicationAvailable: false,
      report: { heureDebutSoiree: '20:00:00', semaines: [], animateurs: [] },
    }),
  );
  const planningApi = {
    equityReport: vi.fn(() =>
      Promise.resolve({
        heureDebutSoiree: '20:00:00',
        semaines: [],
        lignes: [],
        syntheses: {},
        colonnesSolveur: [],
      }),
    ),
    hoursReport,
  };

  async function monter(path = '/diagnostic?onglet=charge'): Promise<ChargeTab> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideLocationMocks(),
        { provide: PlanningApi, useValue: planningApi },
        {
          provide: PlanningStateService,
          useValue: {
            loadForDisplay: () =>
              Promise.resolve({ animateurs: [ALICE], postes: [POSTE], score: null }),
          },
        },
      ],
    });
    TestBed.inject(Location).replaceState(path);
    fixture = TestBed.createComponent(ChargeTab);
    await fixture.whenStable();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    hoursReport.mockClear();
    planningApi.equityReport.mockClear();
  });

  it('draws the synthesis alone, with the two CSV exports and the source of the hours', async () => {
    await monter();
    const racine = fixture.nativeElement as HTMLElement;

    expect(racine.querySelector('.planning-grille-jour')).toBeNull();
    expect(racine.textContent).toContain('Heures pour la paie (CSV)');
    expect(racine.textContent).toContain('Équité (CSV)');
    expect(racine.querySelector('mat-button-toggle[value="publie"]')).not.toBeNull();
  });

  it('reads its state from the address, and writes it back', async () => {
    const page = await monter(
      '/diagnostic?onglet=charge&q=Ali&sort=heuresTotal&dir=desc&sansRepos=1&source=persiste',
    );

    expect(page['filtre']()).toBe('Ali');
    expect(page['tri']()).toEqual({ active: 'heuresTotal', direction: 'desc' });
    expect(page['noRestOnly']()).toBe(true);
    expect(hoursReport).toHaveBeenLastCalledWith('persiste');

    page['noRestOnly'].set(false);
    page['hoursSource'].set(null);
    TestBed.tick();
    await fixture.whenStable();
    const url = TestBed.inject(Location).path();
    expect(url).toContain('onglet=charge');
    expect(url).toContain('q=Ali');
    expect(url).toContain('sort=heuresTotal');
    expect(url).not.toContain('sansRepos');
    expect(url).not.toContain('source=');
    expect(hoursReport).toHaveBeenLastCalledWith(null);
  });
});
