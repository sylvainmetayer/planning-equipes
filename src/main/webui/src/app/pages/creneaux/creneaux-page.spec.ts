// Sorting, the conditional « Famille » column, multi-selection, the feasibility
// index and the auto-slicing round-trip of the timeslots table. The component
// is created but never rendered, so this stays a logic test (the project
// favours those over full DOM rendering) — same shape as
// `animateurs-page.spec.ts`.
//
// Written as the safety net the `<app-reference-table>` extraction needs.
// `decoupage.spec.ts` owns the per-day summary; what is pinned here is the
// page that drives it.

import { provideZonelessChangeDetection, Signal, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Sort } from '@angular/material/sort';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { ConfirmService } from '../../shared/confirm-dialog';
import { CauseInfaisabilite, Creneau } from '../../core/models';
import { CreneauxPage } from './creneaux-page';

function creneau(overrides: Partial<Creneau> & { id: number; jour: number }): Creneau {
  return { date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function cause(manque: number): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite: 'CRITIQUE',
    message: "Manque d'animateurs.",
    creneauId: '1',
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    standIds: [],
    demande: 6,
    capacite: 6 - manque,
    manque
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: Signal<string[]>;
  afficherFamilles: Signal<boolean>;
  sort: WritableSignal<Sort>;
  creneauxAffiches: Signal<Creneau[]>;
  selection: TableSelection<number>;
  causeParCreneau: Signal<Map<number, CauseInfaisabilite>>;
  editingLocked: Signal<boolean>;
  previewVacations: Signal<Creneau[] | null>;
  previewLoading: Signal<boolean>;
  genererLoading: Signal<boolean>;
  resumeDecoupage: Signal<unknown[]>;
  previsualiserDecoupage: () => Promise<void>;
  genererDecoupage: () => Promise<void>;
  remove: (creneau: Creneau) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
};

describe('CreneauxPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn()
  };
  const api = { get: vi.fn(), post: vi.fn() };
  const confirm = { ask: vi.fn() };
  const notifications = { notify: vi.fn() };
  const resolution = { reload: vi.fn(async () => undefined) };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };
  const causeParCreneauId = signal(new Map<string, CauseInfaisabilite>());

  beforeEach(() => {
    for (const stub of [crud.reload, crud.remove, crud.removeMany, crud.reportError, resolution.reload]) {
      stub.mockClear();
    }
    api.get.mockReset();
    api.post.mockReset();
    confirm.ask.mockReset();
    notifications.notify.mockReset();
    dialog.open.mockClear();
    causeParCreneauId.set(new Map());
    confirm.ask.mockResolvedValue(true);
    api.get.mockResolvedValue([]);
    api.post.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        { provide: PlanningResolutionStore, useValue: resolution },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParCreneauId }
        }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(creneaux: Creneau[] = []): PageInternals {
    referenceData.creneaux.set(creneaux);
    return TestBed.createComponent(CreneauxPage).componentInstance as unknown as PageInternals;
  }

  it('loads the referential and the diagnostic on entry', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('the conditional « Famille » column', () => {
    // A group generated with N stagger families holds N same-looking variants
    // of every slot; without the column they read as inexplicable duplicates,
    // and with it they are noise on the groups that have a single family.
    it('hides the column when no displayed slot carries a family', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2, famille: 0 })]);

      expect(page.afficherFamilles()).toBe(false);
      expect(page.columns()).not.toContain('famille');
    });

    it('shows the column as soon as one displayed slot carries a family', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2, famille: 1 })]);

      expect(page.afficherFamilles()).toBe(true);
      expect(page.columns()).toContain('famille');
    });

    it('keeps the checkbox and actions columns around the data ones either way', () => {
      const page = createPage([creneau({ id: 1, jour: 1, famille: 2 })]);

      expect(page.columns()[0]).toBe('select');
      expect(page.columns().at(-1)).toBe('actions');
    });
  });

  describe('sorting', () => {
    // `store.creneaux()` is already chronological, and the sort is stable, so
    // an unsorted view must not reorder anything.
    it('keeps the store order while no sort is applied', () => {
      const page = createPage([creneau({ id: 9, jour: 3 }), creneau({ id: 1, jour: 1 })]);

      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([9, 1]);
    });

    it('sorts chronologically by day then start time, and reverses on descending', () => {
      const page = createPage([
        creneau({ id: 3, jour: 2, heureDebut: '09:00' }),
        creneau({ id: 1, jour: 1, heureDebut: '14:00' }),
        creneau({ id: 2, jour: 1, heureDebut: '09:00' })
      ]);

      page.sort.set({ active: 'jour', direction: 'asc' });
      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([2, 1, 3]);

      page.sort.set({ active: 'jour', direction: 'desc' });
      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([3, 1, 2]);
    });

    // The worst slots must be groupable, which is why this column sorts on the
    // shortfall and not on the presence of a badge.
    it('sorts the problem column on the shortfall, worst last on ascending', () => {
      const page = createPage([
        creneau({ id: 1, jour: 1 }),
        creneau({ id: 2, jour: 2 }),
        creneau({ id: 3, jour: 3 })
      ]);
      causeParCreneauId.set(
        new Map([
          ['1', cause(2)],
          ['3', cause(5)]
        ])
      );

      page.sort.set({ active: 'probleme', direction: 'asc' });

      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([2, 1, 3]);
    });

    it('leaves the store untouched while sorting', () => {
      const page = createPage([creneau({ id: 9, jour: 3 }), creneau({ id: 1, jour: 1 })]);

      page.sort.set({ active: 'jour', direction: 'asc' });
      page.creneauxAffiches();

      expect(referenceData.creneaux().map((row) => row.id)).toEqual([9, 1]);
    });
  });

  describe('the feasibility index', () => {
    // The report carries `creneauId` as a string while `Creneau.id` is a
    // number: the normalisation happens once, here, not in every template.
    it('re-keys the report causes on the numeric slot id', () => {
      const page = createPage([creneau({ id: 12, jour: 1 })]);
      causeParCreneauId.set(new Map([['12', cause(3)]]));

      expect(page.causeParCreneau().get(12)?.manque).toBe(3);
    });

    it('holds nothing while no diagnostic has been loaded', () => {
      const page = createPage([creneau({ id: 12, jour: 1 })]);

      expect(page.causeParCreneau().size).toBe(0);
    });

    it('ignores a cause naming a slot the referential no longer holds', () => {
      const page = createPage([creneau({ id: 12, jour: 1 })]);
      causeParCreneauId.set(new Map([['99', cause(3)]]));

      expect(page.causeParCreneau().size).toBe(0);
    });
  });

  describe('multi-selection', () => {
    it('deletes exactly the ticked rows', async () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 }), creneau({ id: 3, jour: 3 })]);

      page.selection.toggle(1);
      page.selection.toggle(3);
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('creneaux', [1, 3], expect.anything());
    });

    it('forgets a slot deleted in the meantime', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 })]);
      page.selection.toggleAll();

      referenceData.creneaux.set([creneau({ id: 2, jour: 2 })]);

      expect(page.selection.selectedIds()).toEqual([2]);
    });

    // The selection is keyed on the displayed rows, not on the store: it
    // therefore reports them in the order the table shows. On the other
    // reference pages the same keying is what makes "tout sélectionner" follow
    // the quick filter; this page has no filter left since the créneau groups
    // were dropped, so the display order is all that is observable of it.
    it('reports the selection in the displayed order, not the store order', () => {
      const page = createPage([
        creneau({ id: 1, jour: 1 }),
        creneau({ id: 2, jour: 2 }),
        creneau({ id: 3, jour: 3 })
      ]);
      page.sort.set({ active: 'jour', direction: 'desc' });

      page.selection.toggleAll();

      expect(page.selection.selectedIds()).toEqual([3, 2, 1]);
      expect(referenceData.creneaux().map((row) => row.id)).toEqual([1, 2, 3]);
    });

    it('hands the bulk-edit dialog exactly the selected slots', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 })]);

      page.selection.toggle(2);
      page.editSelection();

      expect(dialog.open).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ data: { creneaux: [expect.objectContaining({ id: 2 })] } })
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([creneau({ id: 1, jour: 1 })]);

      await page.remove(creneau({ id: 1, jour: 1 }));

      expect(crud.remove).toHaveBeenCalledWith('creneaux', 1, expect.anything());
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  describe('previewing the auto-slicing', () => {
    it('drops the previous preview before asking for a new one', async () => {
      const page = createPage();
      api.get.mockResolvedValue([creneau({ id: 1, jour: 1 })]);
      await page.previsualiserDecoupage();
      expect(page.previewVacations()).toHaveLength(1);

      api.get.mockRejectedValue(new Error('Découpage impossible.'));
      await page.previsualiserDecoupage();

      expect(page.previewVacations()).toBeNull();
      expect(page.resumeDecoupage()).toEqual([]);
      expect(crud.reportError).toHaveBeenCalledOnce();
    });

    it('summarises the previewed vacations by day', async () => {
      const page = createPage();
      api.get.mockResolvedValue([
        creneau({ id: 1, jour: 1, date: '2026-08-01' }),
        creneau({ id: 2, jour: 1, date: '2026-08-01' }),
        creneau({ id: 3, jour: 2, date: '2026-08-02' })
      ]);

      await page.previsualiserDecoupage();

      expect(api.get).toHaveBeenCalledWith('/api/decoupage/preview');
      expect(page.resumeDecoupage()).toHaveLength(2);
      expect(page.previewLoading()).toBe(false);
    });

    it('lowers the in-flight flag even when the preview fails', async () => {
      const page = createPage();
      api.get.mockRejectedValue(new Error('Découpage impossible.'));

      await page.previsualiserDecoupage();

      expect(page.previewLoading()).toBe(false);
    });
  });

  describe('generating the auto-slicing', () => {
    // The generation replaces the edition's créneaux and erases the persisted
    // plan with them: it is confirmed first, and flagged as dangerous.
    it('writes nothing when the confirmation is refused', async () => {
      const page = createPage();
      confirm.ask.mockResolvedValue(false);

      await page.genererDecoupage();

      expect(confirm.ask).toHaveBeenCalledWith(expect.objectContaining({ danger: true }));
      expect(api.post).not.toHaveBeenCalled();
    });

    it('generates, then reloads both the referential and the resolution state', async () => {
      const page = createPage();
      crud.reload.mockClear();

      await page.genererDecoupage();

      expect(api.post).toHaveBeenCalledExactlyOnceWith('/api/decoupage/generer', {});
      expect(crud.reload).toHaveBeenCalledOnce();
      expect(resolution.reload).toHaveBeenCalledOnce();
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('reports a failed generation and reloads nothing', async () => {
      const page = createPage();
      api.post.mockRejectedValue(new Error('Découpage refusé.'));
      crud.reload.mockClear();

      await page.genererDecoupage();

      expect(crud.reportError).toHaveBeenCalledOnce();
      expect(crud.reload).not.toHaveBeenCalled();
      expect(notifications.notify).not.toHaveBeenCalled();
      expect(page.genererLoading()).toBe(false);
    });
  });

  it('exposes the editing lock as the job service sees it, not as its own copy', () => {
    const page = createPage();

    expect(page.editingLocked()).toBe(false);
  });
});
