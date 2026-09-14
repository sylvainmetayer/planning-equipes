// The Exports screen: what the CSV card offers, what it refuses to offer, the
// one thing the URL must carry — only the referentials that are ticked — and
// the scenario card next to it, which writes the whole edition in one file.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExportCsvApi } from '../../core/api/export-csv-api';
import { CIBLES_EXPORT_CSV } from '../../core/api/imports-api';
import { PlanningApi } from '../../core/api/planning-api';
import { ExportsPage } from './exports-page';

describe('ExportsPage', () => {
  const api = { volumes: vi.fn(), telecharger: vi.fn() };
  const planningApi = { exportScenario: vi.fn() };

  beforeEach(() => {
    api.volumes.mockReset();
    api.telecharger.mockReset();
    planningApi.exportScenario.mockReset();
    planningApi.exportScenario.mockResolvedValue('scenario.yaml téléchargé (application/x-yaml).');
    api.volumes.mockResolvedValue({
      TYPOLOGIES: 7,
      EMPLACEMENTS: 3,
      STANDS: 12,
      CRENEAUX: 18,
      JOURNEES_TYPES: 2,
      ANIMATEURS: 0,
    });
    api.telecharger.mockResolvedValue('referentiels-csv.zip téléchargé (application/zip).');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ExportCsvApi, useValue: api },
        { provide: PlanningApi, useValue: planningApi },
      ],
    });
  });

  type Internals = {
    basculer: (target: string, coche: boolean) => void;
    telecharger: () => Promise<void>;
    peutTelecharger: () => boolean;
    total: () => number;
    exporterScenario: () => Promise<void>;
  };

  async function monter(): Promise<{
    fixture: ComponentFixture<ExportsPage>;
    page: Internals;
    racine: HTMLElement;
  }> {
    const fixture = TestBed.createComponent(ExportsPage);
    await fixture.whenStable();
    return {
      fixture,
      page: fixture.componentInstance as unknown as Internals,
      racine: fixture.nativeElement as HTMLElement,
    };
  }

  it('lists each referential with its file and its row count, all ticked to begin with', async () => {
    const { racine, page } = await monter();

    const lignes = [...racine.querySelectorAll('.export-csv-liste li')];
    expect(lignes).toHaveLength(6);
    expect(lignes[0].textContent).toContain('Typologies');
    expect(lignes[0].textContent).toContain('typologies.csv');
    expect(lignes[0].textContent).toContain('7 ligne(s)');
    // The dates sit between the stands and the animateurs, as on the import
    // screen: an off day only survives where the timeslot already exists.
    expect(lignes[3].textContent).toContain('creneaux.csv');
    expect(lignes[4].textContent).toContain('journees-types.csv');
    expect(racine.querySelectorAll('mat-checkbox input:checked')).toHaveLength(6);
    expect(page.total()).toBe(42);
  });

  /** Ticked but empty is worth saying: the archive carries a header and nothing else. */
  it('warns about a referential ticked while it holds nothing', async () => {
    const { racine } = await monter();

    expect(racine.textContent).toContain('Coché mais vide');
  });

  it('downloads only what stays ticked, and says so in the button', async () => {
    const { fixture, page } = await monter();

    page.basculer('ANIMATEURS', false);
    page.basculer('EMPLACEMENTS', false);
    page.basculer('JOURNEES_TYPES', false);
    await fixture.whenStable();
    expect(page.total()).toBe(37);

    await page.telecharger();

    expect(api.telecharger).toHaveBeenCalledOnce();
    expect(api.telecharger.mock.calls[0][0]).toEqual(['TYPOLOGIES', 'STANDS', 'CRENEAUX']);
  });

  it('offers nothing to download once every box is cleared', async () => {
    const { fixture, page } = await monter();

    for (const target of CIBLES_EXPORT_CSV) {
      page.basculer(target, false);
    }
    await fixture.whenStable();

    expect(page.peutTelecharger()).toBe(false);
    await page.telecharger();
    expect(api.telecharger).not.toHaveBeenCalled();
  });

  it('reports a failed download rather than pretending it worked', async () => {
    api.telecharger.mockRejectedValue(new Error('réseau coupé'));
    const { fixture, page, racine } = await monter();

    await page.telecharger();
    await fixture.whenStable();

    expect(racine.querySelector('.field-error')!.textContent).toContain('réseau coupé');
  });

  // The second card: the whole edition as one scenario file. Read-only, so it
  // is never gated by the solver lock the imports follow.
  it('writes the current edition out as a scenario, and says where it went', async () => {
    const { fixture, page, racine } = await monter();

    await page.exporterScenario();
    await fixture.whenStable();

    expect(planningApi.exportScenario).toHaveBeenCalledOnce();
    expect(racine.textContent).toContain('scenario.yaml téléchargé');
  });

  it('reports a failed scenario export instead of leaving the card silent', async () => {
    planningApi.exportScenario.mockRejectedValue(new Error('réseau coupé'));
    const { fixture, page, racine } = await monter();

    await page.exporterScenario();
    await fixture.whenStable();

    expect(racine.textContent).toContain('réseau coupé');
  });
});
