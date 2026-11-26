// « Mesures d'édition »: the view rides in `?vue=`, and the indicators view
// lists every edition's solves and deletes the row the operator picked.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { KpiHistoriqueEntry, PlanningKpi } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { IndicateursView } from './indicateurs-view';
import { readMesuresView } from './mesures-page';

describe('readMesuresView', () => {
  it('opens the realised by default and the indicators on request', () => {
    expect(readMesuresView(null)).toBe('realise');
    expect(readMesuresView('inconnu')).toBe('realise');
    expect(readMesuresView('indicateurs')).toBe('indicateurs');
  });
});

const KPI: PlanningKpi = {
  score: '0hard/-12medium/-40soft',
  scoreHard: 0,
  scoreMedium: -12,
  scoreSoft: -40,
  postesTotal: 10,
  postesPourvus: 9,
  animateursAffectes: 6,
  standsDistincts: 4,
  creneauxDistincts: 5,
  heuresTotal: 60,
  heuresMoyenne: 10,
  heuresEcartType: 2,
  heuresMin: 4,
  heuresMax: 18,
  heuresIncompletes: false,
  modificationsManuelles: 0,
  tauxModificationsManuelles: 0,
  dureeSolveSecondes: 60,
  violationsParContrainte: {},
  scoreMediumHorsPlancher: null,
  plancherMedium: null,
  journeesSousConsigne: null,
  heuresFermeesParConsigne: null,
};

describe('IndicateursView', () => {
  const entry = (id: number, editionNom: string | null): KpiHistoriqueEntry => ({
    id,
    editionId: `E${id}`,
    editionNom,
    kpi: KPI,
    creeLe: '2026-07-01T10:00:00Z',
  });
  const api = {
    kpiHistory: vi.fn<AnalysesApi['kpiHistory']>(),
    deleteKpiEntry: vi.fn<AnalysesApi['deleteKpiEntry']>(),
  };
  const confirm = { ask: vi.fn(() => Promise.resolve(true)) };

  beforeEach(() => {
    api.kpiHistory.mockReset().mockResolvedValue([entry(1, 'Année 2025'), entry(2, null)]);
    api.deleteKpiEntry.mockReset().mockResolvedValue();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: api },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
  });

  it('lists every edition, falling back to the id when the name is gone', async () => {
    const fixture = TestBed.createComponent(IndicateursView);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Année 2025');
    expect(text).toContain('E2');
  });

  it('deletes the row picked, once confirmed', async () => {
    const fixture = TestBed.createComponent(IndicateursView);
    await fixture.whenStable();
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('button.danger-action')
      ?.click();
    await fixture.whenStable();
    expect(api.deleteKpiEntry).toHaveBeenCalledWith(1);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('tr.mat-mdc-row')).toHaveLength(
      1,
    );
  });
});
