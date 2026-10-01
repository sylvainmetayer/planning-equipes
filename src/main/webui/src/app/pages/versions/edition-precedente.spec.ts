// « Réalisé de l'édition précédente » under the Versions table: the measure the
// next edition reads, and nothing at all when no earlier edition left one.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RealiseApi } from '../../core/api/realise-api';
import { PreviousEdition } from '../../core/models';
import { EditionPrecedente } from './edition-precedente';

const MESURE: PreviousEdition = {
  available: true,
  editionId: 'E1',
  editionNom: 'Année 2025',
  firstDay: '2025-07-10',
  lastDay: '2025-07-20',
  countedDays: 11,
  frozenAt: '2025-07-21T01:30:00Z',
  event: {
    publishedSeats: 400,
    keptSeats: 390,
    absences: 20,
    replacements: 12,
    emptySeats: 10,
    removedSeats: 0,
    addedSeats: 3,
    publishedMinutes: 96_000,
    realisedMinutes: 94_800,
    lostMinutes: 1_200,
    absenceRate: 0.05,
    replacementRate: 0.03,
  },
  byTypologie: [],
};

describe('EditionPrecedente', () => {
  const api = { previousEdition: vi.fn<RealiseApi['previousEdition']>() };

  beforeEach(() => {
    api.previousEdition.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: RealiseApi, useValue: api },
      ],
    });
  });

  it("says the previous edition's measure: its name, its dates and its rates", async () => {
    api.previousEdition.mockResolvedValue(MESURE);

    const fixture = TestBed.createComponent(EditionPrecedente);
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Année 2025');
    expect(text).toContain('2025-07-20');
    expect(text).toContain('400');
    expect(text).toMatch(/5\s?%/);
    expect(text).toContain('20 h');
  });

  it('draws nothing when no earlier edition left a measure', async () => {
    api.previousEdition.mockResolvedValue({ ...MESURE, available: false });

    const fixture = TestBed.createComponent(EditionPrecedente);
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('section')).toBeNull();
  });
});
