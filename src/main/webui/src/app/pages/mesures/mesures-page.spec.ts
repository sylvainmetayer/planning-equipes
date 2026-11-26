// « Mesures d'édition »: the view rides in `?vue=`, and the indicators view
// lists every edition's solves and deletes the row the operator picked.

import { Location } from '@angular/common';
import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { KpiHistoriqueEntry, PlanningKpi } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { IndicateursView } from './indicateurs-view';
import { MesuresPage, readMesuresView } from './mesures-page';
import { RealisePage } from '../realise/realise-page';

describe('readMesuresView', () => {
  it('opens the realised by default and the indicators on request', () => {
    expect(readMesuresView(null)).toBe('realise');
    expect(readMesuresView('inconnu')).toBe('realise');
    expect(readMesuresView('indicateurs')).toBe('indicateurs');
  });
});

@Component({ selector: 'app-indicateurs-view', template: 'vue-indicateurs' })
class IndicateursStub {}
@Component({ selector: 'app-realise-page', template: 'vue-realise' })
class RealiseStub {}

describe('MesuresPage', () => {
  const replaceState = vi.fn();
  let path: string;

  function page() {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Location, useValue: { path: () => path, replaceState } },
      ],
    }).overrideComponent(MesuresPage, {
      remove: { imports: [IndicateursView, RealisePage] },
      add: { imports: [IndicateursStub, RealiseStub] },
    });
    return TestBed.createComponent(MesuresPage);
  }

  it('opens the realised by default, the indicators on ?vue=indicateurs', async () => {
    path = '/mesures';
    let fixture = page();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('vue-realise');

    path = '/mesures?vue=indicateurs';
    fixture = page();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('vue-indicateurs');
  });

  it('switches view and writes it in the address', async () => {
    path = '/mesures';
    replaceState.mockReset();
    const fixture = page();
    await fixture.whenStable();
    await fixture.componentInstance['views'].changer('indicateurs');
    await fixture.whenStable();
    expect(replaceState).toHaveBeenCalledWith('/mesures?vue=indicateurs');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('vue-indicateurs');
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

  it('says why the list could not be read', async () => {
    api.kpiHistory.mockRejectedValue(new Error('hors ligne'));
    const fixture = TestBed.createComponent(IndicateursView);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('hors ligne');
  });

  it('keeps the row when the operator declines, or when the server refuses', async () => {
    const fixture = TestBed.createComponent(IndicateursView);
    await fixture.whenStable();
    const rows = () => (fixture.nativeElement as HTMLElement).querySelectorAll('tr.mat-mdc-row');
    const remove = () =>
      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('button.danger-action')
        ?.click();

    confirm.ask.mockResolvedValueOnce(false);
    remove();
    await fixture.whenStable();
    expect(api.deleteKpiEntry).not.toHaveBeenCalled();

    api.deleteKpiEntry.mockRejectedValueOnce(new Error('refusé'));
    remove();
    await fixture.whenStable();
    expect(rows()).toHaveLength(2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('refusé');
  });
});
