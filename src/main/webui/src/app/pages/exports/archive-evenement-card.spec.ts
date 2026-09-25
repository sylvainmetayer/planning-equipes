// The end-of-event archive card: what is ticked to begin with, what is greyed
// out when it would come out empty, and what the download really asks for —
// never a part the screen shows as unavailable.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ArchiveEvenementApi } from '../../core/api/archive-evenement-api';
import { ArchiveAvailability, ArchivePart } from '../../core/models';
import { ArchiveEvenementCard } from './archive-evenement-card';

type Internals = {
  basculer: (part: ArchivePart, coche: boolean) => void;
  telecharger: () => Promise<void>;
  parties: () => ArchivePart[];
  peutTelecharger: () => boolean;
  feuille: { set: (value: boolean) => void };
  message: () => string;
  erreur: () => string;
};

describe('ArchiveEvenementCard', () => {
  const api = {
    availability: vi.fn<() => Promise<ArchiveAvailability>>(),
    telecharger: vi.fn<(parts: readonly ArchivePart[], format: string) => Promise<string>>(),
  };

  beforeEach(() => {
    api.availability.mockReset();
    api.telecharger.mockReset();
    api.telecharger.mockResolvedValue(
      'archive-annee-2026-2026-09-25.zip téléchargé (application/zip).',
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ArchiveEvenementApi, useValue: api },
      ],
    });
  });

  async function mount(disponibilite: ArchiveAvailability) {
    api.availability.mockResolvedValue(disponibilite);
    const fixture = TestBed.createComponent(ArchiveEvenementCard);
    await fixture.whenStable();
    fixture.detectChanges();
    return {
      page: fixture.componentInstance as unknown as Internals,
      racine: fixture.nativeElement as HTMLElement,
    };
  }

  it('ticks everything but the heavy individual documents and the review, the manifest always', async () => {
    const { page, racine } = await mount({
      planResolu: true,
      publie: true,
      resolutionEnCours: false,
    });

    expect(page.parties()).toEqual(['pdfGlobal', 'equite', 'heures', 'referentiels', 'scenario']);
    expect(racine.textContent).toContain('LISEZMOI.txt');
    expect(racine.querySelectorAll('li[data-part]')).toHaveLength(7);

    await page.telecharger();
    expect(api.telecharger).toHaveBeenCalledWith(
      ['pdfGlobal', 'equite', 'heures', 'referentiels', 'scenario'],
      'livret',
    );
    expect(page.message()).toContain('téléchargé');
  });

  it('greys out what reads the plan while there is none, and never asks for it', async () => {
    const { page, racine } = await mount({
      planResolu: false,
      publie: false,
      resolutionEnCours: false,
    });

    const pdf = racine.querySelector('li[data-part="pdfGlobal"]')!;
    expect(pdf.textContent).toContain('aucun plan résolu');
    expect(pdf.querySelector('input')!.disabled).toBe(true);
    expect(racine.querySelector('li[data-part="publication"]')!.textContent).toContain(
      'aucune publication',
    );
    expect(page.parties()).toEqual(['referentiels', 'scenario']);
  });

  it('offers the sheet layout once the individual documents are taken, and sends it', async () => {
    const { page } = await mount({ planResolu: true, publie: true, resolutionEnCours: false });

    page.basculer('individuels', true);
    page.feuille.set(true);
    await page.telecharger();

    expect(api.telecharger).toHaveBeenCalledWith(
      ['pdfGlobal', 'equite', 'heures', 'referentiels', 'scenario', 'individuels'],
      'feuille',
    );
  });

  it('refuses to download nothing, and says why a refused archive failed', async () => {
    const { page } = await mount({ planResolu: true, publie: true, resolutionEnCours: false });
    for (const part of ['pdfGlobal', 'equite', 'heures', 'referentiels', 'scenario'] as const) {
      page.basculer(part, false);
    }
    expect(page.peutTelecharger()).toBe(false);
    await page.telecharger();
    expect(api.telecharger).not.toHaveBeenCalled();

    page.basculer('scenario', true);
    api.telecharger.mockRejectedValueOnce(new Error('Aucune donnée de référence à exporter.'));
    await page.telecharger();
    expect(page.erreur()).toContain('Aucune donnée');
  });

  it('says the archive carries the last persisted plan while a solve runs', async () => {
    const { racine } = await mount({ planResolu: true, publie: false, resolutionEnCours: true });

    expect(racine.textContent).toContain('Une résolution est en cours');
  });
});
