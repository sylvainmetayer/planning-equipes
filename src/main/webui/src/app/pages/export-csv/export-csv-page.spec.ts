// The CSV export screen: what it offers, what it refuses to offer, and the one
// thing the URL must carry — only the referentials that are ticked.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExportCsvApi } from '../../core/api/export-csv-api';
import { ExportCsvPage } from './export-csv-page';

describe('ExportCsvPage', () => {
  const api = { volumes: vi.fn(), telecharger: vi.fn() };

  beforeEach(() => {
    api.volumes.mockReset();
    api.telecharger.mockReset();
    api.volumes.mockResolvedValue({
      TYPOLOGIES: 7,
      EMPLACEMENTS: 3,
      STANDS: 12,
      ANIMATEURS: 0,
    });
    api.telecharger.mockResolvedValue('referentiels-csv.zip téléchargé (application/zip).');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ExportCsvApi, useValue: api },
      ],
    });
  });

  type Internals = {
    basculer: (cible: string, coche: boolean) => void;
    telecharger: () => Promise<void>;
    peutTelecharger: () => boolean;
    total: () => number;
  };

  async function monter(): Promise<{
    fixture: ComponentFixture<ExportCsvPage>;
    page: Internals;
    racine: HTMLElement;
  }> {
    const fixture = TestBed.createComponent(ExportCsvPage);
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
    expect(lignes).toHaveLength(4);
    expect(lignes[0].textContent).toContain('Typologies');
    expect(lignes[0].textContent).toContain('typologies.csv');
    expect(lignes[0].textContent).toContain('7 ligne(s)');
    expect(racine.querySelectorAll('mat-checkbox input:checked')).toHaveLength(4);
    expect(page.total()).toBe(22);
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
    await fixture.whenStable();
    expect(page.total()).toBe(19);

    await page.telecharger();

    expect(api.telecharger).toHaveBeenCalledOnce();
    expect(api.telecharger.mock.calls[0][0]).toEqual(['TYPOLOGIES', 'STANDS']);
  });

  it('offers nothing to download once every box is cleared', async () => {
    const { fixture, page } = await monter();

    for (const cible of ['TYPOLOGIES', 'EMPLACEMENTS', 'STANDS', 'ANIMATEURS']) {
      page.basculer(cible, false);
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
});
