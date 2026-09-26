// « Versions du plan »: rendered for real on mocked stores. What the page
// decides beyond listing: which rows can be restored, compared and ticked,
// that two ticks open the comparator beside the table with the older side as
// the reference, and that restoring waits while a solve runs.

import { Component, input, output, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanningApi } from '../../core/api/planning-api';
import { EditionStore } from '../../core/edition.store';
import { KpiHistoriqueEntry, PlanningKpi, PlanSnapshot } from '../../core/models';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ComparaisonPanel } from './comparaison-panel';
import { VersionsPage } from './versions-page';

@Component({ selector: 'app-comparaison-panel', template: '' })
class FakePanel {
  readonly base = input.required<string>();
  readonly variante = input.required<string>();
  readonly snapshots = input<readonly PlanSnapshot[]>([]);
  readonly closed = output<void>();
}

function kpi(): PlanningKpi {
  return {
    score: '0hard/-12medium/-40soft',
    scoreHard: 0,
    scoreMedium: -12,
    scoreSoft: -40,
    postesTotal: 200,
    postesPourvus: 190,
    animateursAffectes: 120,
    standsDistincts: 40,
    creneauxDistincts: 60,
    heuresTotal: 1200,
    heuresMoyenne: 10,
    heuresEcartType: 2,
    heuresMin: 4,
    heuresMax: 18,
    heuresIncompletes: false,
    modificationsManuelles: 0,
    tauxModificationsManuelles: 0,
    dureeSolveSecondes: 90,
    violationsParContrainte: {},
    scoreMediumHorsPlancher: null,
    plancherMedium: null,
    journeesSousConsigne: null,
    heuresFermeesParConsigne: null,
  };
}

function snapshot(id: number, creeLe: string, overrides: Partial<PlanSnapshot> = {}): PlanSnapshot {
  return {
    id,
    libelle: `Instantané ${id}`,
    automatique: false,
    score: null,
    nombreAffectations: 190,
    creeLe,
    editionId: 'E1',
    editionNom: null,
    kpi: kpi(),
    referenceModifieLe: null,
    perime: false,
    publieLe: null,
    ...overrides,
  };
}

const ENTRIES: KpiHistoriqueEntry[] = [
  { id: 1, editionId: 'E1', editionNom: null, kpi: kpi(), creeLe: '2026-08-03T10:00:00Z' },
];

type Internals = { ticked: () => string[] };

describe('VersionsPage', () => {
  let fixture: ComponentFixture<VersionsPage>;
  const editingLocked = signal(false);
  const planningApi = { comparableSnapshots: vi.fn(async (): Promise<PlanSnapshot[]> => []) };
  const snapshots = signal<PlanSnapshot[]>([]);
  const store = {
    snapshots,
    chargement: signal(false),
    reload: vi.fn(async () => undefined),
    capturer: vi.fn(),
    supprimer: vi.fn(async () => undefined),
    restaurer: vi.fn(),
  };

  async function rendre(liste: PlanSnapshot[]): Promise<HTMLElement> {
    snapshots.set(liste);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanSnapshotStore, useValue: store },
        {
          provide: AnalysesApi,
          useValue: { kpiHistory: vi.fn(async () => ENTRIES), deleteKpiEntry: vi.fn() },
        },
        { provide: PlanningApi, useValue: planningApi },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'E1' }) } },
        { provide: SolverJobService, useValue: { editingLocked } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn() } },
        { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
        { provide: MatDialog, useValue: {} },
      ],
    });
    TestBed.overrideComponent(VersionsPage, {
      remove: { imports: [ComparaisonPanel] },
      add: { imports: [FakePanel] },
    });
    fixture = TestBed.createComponent(VersionsPage);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      // The solves and the snapshots land separately: wait for both.
      expect(racine().querySelectorAll('tbody tr').length).toBe(liste.length + 2);
    });
    return racine();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function lignes(): HTMLElement[] {
    return Array.from(racine().querySelectorAll('tbody tr'));
  }

  function checkbox(ligne: HTMLElement): HTMLInputElement | null {
    return ligne.querySelector('input[type="checkbox"]');
  }

  beforeEach(() => {
    editingLocked.set(false);
    store.restaurer.mockReset();
  });

  it('heads the chronology with the plan in place, and says each result in words', async () => {
    await rendre([snapshot(7, '2026-08-01T10:00:00Z'), snapshot(8, '2026-08-02T10:00:00Z')]);

    const texts = lignes().map((ligne) => ligne.textContent!.replace(/\s+/g, ' '));
    expect(texts[0]).toContain('Plan en place');
    expect(texts[1]).toContain('Résolution terminée en 1 min 30 s');
    expect(texts[2]).toContain('Instantané 8');
    expect(texts[3]).toContain('Instantané 7');
    expect(texts[1]).toContain('190 places pourvues sur 200.');
    expect(racine().textContent).not.toMatch(/-12medium|hard\//);
  });

  it('restores and compares a snapshot, never a solve, which keeps no plan', async () => {
    await rendre([snapshot(7, '2026-08-01T10:00:00Z')]);
    const [courant, solve, capture] = lignes();

    expect(checkbox(courant)).not.toBeNull();
    expect(checkbox(solve)).toBeNull();
    expect(checkbox(capture)).not.toBeNull();
    expect(capture.querySelector('button[aria-label="Restaurer ce plan"]')).not.toBeNull();
    expect(capture.querySelector(`button[aria-label="Comparer avec l'actuel"]`)).not.toBeNull();
    expect(solve.querySelector('button[aria-label="Restaurer ce plan"]')).toBeNull();
  });

  it('opens the comparator beside the table on two ticks, the older side as reference', async () => {
    await rendre([snapshot(7, '2026-08-01T10:00:00Z'), snapshot(8, '2026-08-02T10:00:00Z')]);
    const [courant, , recent] = lignes();

    checkbox(courant)!.click();
    checkbox(recent)!.click();
    await fixture.whenStable();
    expect((fixture.componentInstance as unknown as Internals).ticked()).toEqual(['courant', '8']);
    // A third tick is refused rather than dropping one silently.
    expect(checkbox(lignes()[3])!.disabled).toBe(true);

    const comparer = Array.from(racine().querySelectorAll('.versions-toolbar button')).find(
      (button) => button.textContent!.includes('Comparer'),
    ) as HTMLButtonElement;
    comparer.click();
    await fixture.whenStable();

    const panel = fixture.debugElement.query((element) => element.name === 'app-comparaison-panel')
      .componentInstance as FakePanel;
    expect(panel.base()).toBe('8');
    expect(panel.variante()).toBe('courant');
    expect(racine().querySelector('.versions-layout-panneau')).not.toBeNull();
  });

  it('keeps « Restaurer » off while a solve runs', async () => {
    editingLocked.set(true);
    await rendre([snapshot(7, '2026-08-01T10:00:00Z')]);

    const restaurer = lignes()[2].querySelector(
      'button[aria-label="Restaurer ce plan"]',
    ) as HTMLButtonElement;
    expect(restaurer.disabled).toBe(true);
    expect(racine().textContent).toContain('la restauration est désactivée');
  });

  it('refuses to delete the plan the animateurs hold', async () => {
    await rendre([snapshot(7, '2026-08-01T10:00:00Z', { publieLe: '2026-08-01T11:00:00Z' })]);

    const deleteButton = lignes()[2].querySelector('button.danger-action') as HTMLButtonElement;
    expect(deleteButton.disabled).toBe(true);
    expect(lignes()[2].textContent).toContain('plan publié');
  });

  it('lists every edition on demand, and restores only from the edition in use', async () => {
    await rendre([snapshot(7, '2026-08-01T10:00:00Z')]);
    planningApi.comparableSnapshots.mockResolvedValue([
      snapshot(7, '2026-08-01T10:00:00Z'),
      snapshot(9, '2026-07-01T10:00:00Z', { editionId: 'E0', editionNom: 'Année 2025' }),
    ]);

    (
      fixture.componentInstance as unknown as { showAllEditions: (all: boolean) => void }
    ).showAllEditions(true);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(lignes()).toHaveLength(4);
    });

    const ancienne = lignes()[3];
    expect(ancienne.textContent).toContain('Année 2025');
    expect(
      (ancienne.querySelector('button[aria-label="Restaurer ce plan"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(checkbox(ancienne)!.disabled).toBe(false);
  });
});
