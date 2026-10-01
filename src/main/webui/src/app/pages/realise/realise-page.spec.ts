// The Réalisé vs planifié page over a typed fake of its API: what it says
// before the first publication and when the past rule is off, the grid it
// draws, the detail a cell opens — names on this admin screen — and the CSV.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RealiseApi } from '../../core/api/realise-api';
import { CellDetail, GapCounts, PreviousEdition, RealisedVsPlanned } from '../../core/models';
import { RealisePage } from './realise-page';

function counts(overrides: Partial<GapCounts> = {}): GapCounts {
  return {
    publishedSeats: 2,
    keptSeats: 2,
    absences: 1,
    replacements: 1,
    emptySeats: 0,
    removedSeats: 0,
    addedSeats: 0,
    publishedMinutes: 240,
    realisedMinutes: 240,
    lostMinutes: 0,
    absenceRate: 0.5,
    replacementRate: 0.5,
    ...overrides,
  };
}

function rapport(overrides: Partial<RealisedVsPlanned> = {}): RealisedVsPlanned {
  return {
    referenceAvailable: true,
    nature: 'DECLARED',
    frozenPast: true,
    today: '2026-07-14',
    days: [
      {
        date: '2026-07-11',
        counted: true,
        referencePublishedAt: '2026-07-01T10:00:00Z',
        lateReference: false,
      },
    ],
    cells: [{ standId: 'cirque', standNom: 'Cirque', date: '2026-07-11', counts: counts() }],
    byStand: [{ key: 'cirque', label: 'Cirque', counts: counts() }],
    byDay: [{ key: '2026-07-11', label: '2026-07-11', counts: counts() }],
    byTypologie: [{ key: 'T1', label: 'Jeux', counts: counts() }],
    event: counts(),
    ...overrides,
  };
}

const DETAIL: CellDetail = {
  date: '2026-07-11',
  standId: 'cirque',
  standNom: 'Cirque',
  referenceAvailable: true,
  referencePublishedAt: '2026-07-01T10:00:00Z',
  lateReference: false,
  counts: counts(),
  lines: [
    {
      seat: {
        standId: 'cirque',
        standNom: 'Cirque',
        date: '2026-07-11',
        heureDebut: '14:00:00',
        heureFin: '18:00:00',
        heureDebutAvant: null,
        heureFinAvant: null,
        avant: { animateurId: 'camille', nomAffiche: 'Camille Durand' },
        apres: { animateurId: 'sasha', nomAffiche: 'Sasha Roy' },
        type: 'REMPLACE',
      },
      outcome: 'REPLACED',
      absence: true,
    },
  ],
};

const NO_PREVIOUS_EDITION: PreviousEdition = {
  available: false,
  editionId: null,
  editionNom: null,
  firstDay: null,
  lastDay: null,
  countedDays: null,
  frozenAt: null,
  event: null,
  byTypologie: [],
};

/** The four calls of the page, each a typed mock of the real signature. */
function fakeApi() {
  return {
    report: vi.fn<RealiseApi['report']>(() => Promise.resolve(rapport())),
    detail: vi.fn<RealiseApi['detail']>(() => Promise.resolve(DETAIL)),
    exportCsv: vi.fn<RealiseApi['exportCsv']>(() =>
      Promise.resolve('realise-vs-planifie.csv téléchargé'),
    ),
    previousEdition: vi.fn<RealiseApi['previousEdition']>(() =>
      Promise.resolve(NO_PREVIOUS_EDITION),
    ),
  };
}

async function render(): Promise<ComponentFixture<RealisePage>> {
  const fixture = TestBed.createComponent(RealisePage);
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

function pageText(fixture: ComponentFixture<RealisePage>): string {
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

describe('RealisePage', () => {
  let api: ReturnType<typeof fakeApi>;

  beforeEach(() => {
    api = fakeApi();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: RealiseApi, useValue: api },
      ],
    });
  });

  it('says there is no reference before the first publication, and draws no grid', async () => {
    api.report.mockResolvedValue(
      rapport({ referenceAvailable: false, cells: [], byStand: [], byDay: [], byTypologie: [] }),
    );

    const fixture = await render();

    expect(pageText(fixture)).toContain("Rien n'a encore été publié");
    expect((fixture.nativeElement as HTMLElement).querySelector('app-planning-grille')).toBeNull();
  });

  it('says the realised is declared, and warns when the past is not frozen', async () => {
    api.report.mockResolvedValue(rapport({ frozenPast: false }));

    const fixture = await render();

    expect(pageText(fixture)).toContain('Réalisé déclaré');
    expect(pageText(fixture)).toContain('Le passé figé est désactivé');
  });

  it('opens the shifts of a cell under the grid, holders named', async () => {
    const fixture = await render();
    const racine = fixture.nativeElement as HTMLElement;

    const cellule = racine.querySelector<HTMLElement>('td.planning-grille-case-active');
    expect(cellule).not.toBeNull();
    cellule?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.detail).toHaveBeenCalledWith('2026-07-11', 'cirque');
    const detail = racine.querySelector('.realise-detail');
    expect(detail?.textContent).toContain('Camille Durand → Sasha Roy');
    expect(detail?.textContent).toContain('Remplacé');
    expect(detail?.textContent).toContain('absence');
  });

  it('downloads the CSV through the API and says so', async () => {
    const fixture = await render();
    const bouton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ).find((candidat) => candidat.textContent?.includes('Exporter en CSV'));

    bouton?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.exportCsv).toHaveBeenCalledOnce();
    expect(pageText(fixture)).toContain('realise-vs-planifie.csv téléchargé');
  });
});
