// What this screen has to get right about freshness (issue #170): restoring a
// snapshot whose referential has moved since the capture asks a second, named
// question before forcing, and a plain restore never carries the override.
//
// The component is created but never rendered — the project favours logic
// tests — so what is pinned here is the sequence of calls: one restore, the
// staleness refusal turned into a confirmation, and either a second restore
// with `forcer` or nothing at all.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanSnapshot } from '../../core/models';
import { InstantanePerimeError, PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { SnapshotsPage } from './snapshots-page';

function snapshot(overrides: Partial<PlanSnapshot> = {}): PlanSnapshot {
  return {
    id: 7,
    libelle: 'Avant canicule',
    automatique: false,
    score: '0hard/0medium/-12soft',
    nombreAffectations: 190,
    creeLe: '2026-08-18T10:00:00Z',
    editionId: 'DEFAUT',
    editionNom: 'Édition par défaut',
    kpi: null,
    referenceModifieLe: null,
    perime: false,
    ...overrides,
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  restaurer: (snapshot: PlanSnapshot) => Promise<void>;
  fraicheurTooltip: (snapshot: PlanSnapshot) => string;
  error: () => string;
  message: () => string;
};

describe('SnapshotsPage', () => {
  const store = {
    snapshots: () => [] as PlanSnapshot[],
    chargement: () => false,
    reload: vi.fn(),
    restaurer: vi.fn(),
    capturer: vi.fn(),
    supprimer: vi.fn(),
  };
  const confirm = { ask: vi.fn() };
  const planningApi = { persistedCount: vi.fn() };
  const resolution = { reload: vi.fn() };
  const jobs = { editingLocked: () => false };

  beforeEach(() => {
    store.reload.mockReset().mockResolvedValue(undefined);
    store.restaurer.mockReset();
    confirm.ask.mockReset().mockResolvedValue(true);
    planningApi.persistedCount.mockReset().mockResolvedValue({ assignments: 12 });
    resolution.reload.mockReset().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanSnapshotStore, useValue: store },
        { provide: ConfirmService, useValue: confirm },
        { provide: PlanningApi, useValue: planningApi },
        { provide: PlanningResolutionStore, useValue: resolution },
        { provide: SolverJobService, useValue: jobs },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(SnapshotsPage).componentInstance as unknown as PageInternals;
  }

  it('restores without the override when the server raises no staleness', async () => {
    store.restaurer.mockResolvedValue({ restaure: true, affectations: 190 });
    const page = createPage();

    await page.restaurer(snapshot());

    expect(store.restaurer).toHaveBeenCalledExactlyOnceWith(7);
    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(page.error()).toBe('');
    expect(page.message()).toContain('190');
  });

  // The server, not the row on screen, decides: the list may have been loaded
  // before the referential moved. So the refusal is what triggers the question.
  it('asks a second, named question before forcing a stale snapshot back', async () => {
    store.restaurer
      .mockRejectedValueOnce(
        new InstantanePerimeError('Référentiel modifié', '2026-08-19T08:30:00Z'),
      )
      .mockResolvedValueOnce({ restaure: true, affectations: 190 });
    const page = createPage();

    await page.restaurer(snapshot({ perime: true }));

    expect(confirm.ask).toHaveBeenCalledTimes(2);
    expect(String(confirm.ask.mock.calls[1][0].message)).toContain('référentiel');
    expect(store.restaurer).toHaveBeenNthCalledWith(1, 7);
    expect(store.restaurer).toHaveBeenNthCalledWith(2, 7, true);
    expect(page.message()).toContain('190');
  });

  it('writes nothing when the staleness question is declined', async () => {
    store.restaurer.mockRejectedValue(new InstantanePerimeError('Référentiel modifié', null));
    confirm.ask.mockResolvedValueOnce(true).mockResolvedValueOnce(false);
    const page = createPage();

    await page.restaurer(snapshot({ perime: true }));

    expect(store.restaurer).toHaveBeenCalledExactlyOnceWith(7);
    expect(resolution.reload).not.toHaveBeenCalled();
    expect(page.message()).toBe('');
    expect(page.error()).toBe('');
  });

  // A missing reference is not a question: no confirmation lifts it, and the
  // ids it names are the actionable part of the message.
  it('reports any other refusal instead of offering to force it', async () => {
    store.restaurer.mockRejectedValue(new Error('Instantané introuvable.'));
    const page = createPage();

    await page.restaurer(snapshot());

    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(store.restaurer).toHaveBeenCalledExactlyOnceWith(7);
    expect(page.error()).toContain('Instantané introuvable.');
  });

  it('says since when a snapshot is stale, and stays silent about a fresh one', () => {
    const page = createPage();

    expect(page.fraicheurTooltip(snapshot({ perime: false }))).toContain('Aucune modification');
    expect(
      page.fraicheurTooltip(snapshot({ perime: true, referenceModifieLe: '2026-08-19T08:30:00Z' })),
    ).toContain('modifié le');
    expect(page.fraicheurTooltip(snapshot({ perime: true }))).not.toContain('Invalid Date');
  });
});
