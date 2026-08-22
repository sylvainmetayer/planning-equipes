// Filtering, multi-selection and cell labels of the emplacements table,
// including the nearest-neighbour column that mirrors the solver's 300 m
// threshold. The component is created but never rendered, so this stays a
// logic test (the project favours those over full DOM rendering) — same shape
// as `animateurs-page.spec.ts`.
//
// Written as the safety net the `<app-reference-table>` extraction needs.
// `distance.spec.ts` owns the great-circle maths; what is pinned here is the
// column built on top of it.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { Emplacement } from '../../core/models';
import { EmplacementsPage } from './emplacements-page';

// A degree of latitude is ~111 km, so 0.001° ≈ 111 m: near enough to place a
// neighbour deliberately on either side of the 300 m threshold.
function emplacement(id: string, overrides: Partial<Emplacement> = {}): Emplacement {
  return { id, nom: id, latitude: null, longitude: null, ...overrides };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: string[];
  filtre: { set: (value: string) => void };
  emplacementsFiltres: Signal<Emplacement[]>;
  selection: TableSelection<string>;
  editingLocked: Signal<boolean>;
  coordonneesLabel: (emplacement: Emplacement) => string;
  voisinLePlusProche: (emplacement: Emplacement) => string;
  remove: (emplacement: Emplacement) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
};

describe('EmplacementsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0)
  };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.remove.mockClear();
    crud.removeMany.mockClear();
    dialog.open.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get: vi.fn() } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: dialog }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(emplacements: Emplacement[] = []): PageInternals {
    referenceData.emplacements.set(emplacements);
    return TestBed.createComponent(EmplacementsPage).componentInstance as unknown as PageInternals;
  }

  it('loads the referential on entry rather than showing whatever the previous page left', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('quick filter', () => {
    it('shows every emplacement while the filter is empty', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);

      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['prairie', 'halle']);
    });

    it('matches on the id and on the name', () => {
      const page = createPage([
        emplacement('prairie', { nom: 'Grande prairie' }),
        emplacement('halle', { nom: 'Halle couverte' })
      ]);

      page.filtre.set('couverte');
      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['halle']);

      page.filtre.set('prairie');
      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['prairie']);
    });

    it('matches on the coordinates, which is how a misplaced point is found', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.12345, longitude: 1.5 }),
        emplacement('halle', { latitude: 48.9, longitude: 1.5 })
      ]);

      page.filtre.set('47.123');

      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['prairie']);
    });

    it('shows nothing rather than everything when nothing matches', () => {
      const page = createPage([emplacement('prairie')]);

      page.filtre.set('introuvable');

      expect(page.emplacementsFiltres()).toEqual([]);
    });
  });

  describe('multi-selection', () => {
    it('deletes exactly the ticked rows', async () => {
      const page = createPage([emplacement('prairie'), emplacement('halle'), emplacement('chapiteau')]);

      page.selection.toggle('prairie');
      page.selection.toggle('chapiteau');
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('emplacements', ['prairie', 'chapiteau'], expect.anything());
    });

    it('forgets an emplacement deleted in the meantime', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);
      page.selection.toggleAll();

      referenceData.emplacements.set([emplacement('halle')]);

      expect(page.selection.selectedIds()).toEqual(['halle']);
    });

    it('narrows select-all to the filtered rows', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);

      page.filtre.set('prairie');
      page.selection.toggleAll();

      expect(page.selection.selectedIds()).toEqual(['prairie']);
    });

    it('hands the bulk-edit dialog exactly the selected emplacements', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);

      page.selection.toggle('halle');
      page.editSelection();

      expect(dialog.open).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ data: { emplacements: [expect.objectContaining({ id: 'halle' })] } })
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([emplacement('prairie')]);

      await page.remove(emplacement('prairie'));

      expect(crud.remove).toHaveBeenCalledWith('emplacements', 'prairie', expect.anything());
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  describe('coordinates cell', () => {
    it('renders a dash for a place that has never been located', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie'))).toBe('—');
    });

    it('renders a dash when only one of the two coordinates is set', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie', { latitude: 47.1 }))).toBe('—');
      expect(page.coordonneesLabel(emplacement('prairie', { longitude: 1.5 }))).toBe('—');
    });

    it('renders both coordinates to five decimals, which is metre-level precision', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }))).toBe(
        '47.10000, 1.50000'
      );
    });

    // Zero is a valid coordinate; `||` would have turned the equator into a dash.
    it('renders a coordinate of zero rather than treating it as missing', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie', { latitude: 0, longitude: 0 }))).toBe('0.00000, 0.00000');
    });
  });

  describe('nearest-neighbour cell', () => {
    it('says nothing when there is no other located place to measure against', () => {
      const page = createPage([emplacement('prairie', { latitude: 47.1, longitude: 1.5 })]);

      expect(page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }))).toBe('');
    });

    it('says nothing when the place itself has no coordinates', () => {
      const page = createPage([
        emplacement('prairie'),
        emplacement('halle', { latitude: 47.1, longitude: 1.5 })
      ]);

      expect(page.voisinLePlusProche(emplacement('prairie'))).toBe('');
    });

    it('ignores the other places that have no coordinates', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle')
      ]);

      expect(page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }))).toBe('');
    });

    it('names the closest neighbour, not merely the first one found', () => {
      const loin = emplacement('loin', { latitude: 47.11, longitude: 1.5 });
      const proche = emplacement('proche', { latitude: 47.101, longitude: 1.5 });
      const page = createPage([emplacement('prairie', { latitude: 47.1, longitude: 1.5 }), loin, proche]);

      const label = page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }));

      expect(label).toContain('proche');
      expect(label).not.toContain('loin');
    });

    it('never measures a place against itself', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.102, longitude: 1.5 })
      ]);

      const label = page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }));

      expect(label).toContain('halle');
      expect(label).not.toContain('0 m');
    });

    // Mirrors `QualiteConstraints.DISTANCE_ELOIGNEE_METRES` on the server: past
    // that bound the solver penalises a change of emplacement, and the table
    // must say so where the places are edited.
    it('flags a neighbour beyond the 300 m threshold the solver penalises', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.105, longitude: 1.5 })
      ]);

      const label = page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }));

      expect(label).toContain('seuil');
    });

    it('leaves a neighbour within the threshold unflagged', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.102, longitude: 1.5 })
      ]);

      const label = page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }));

      expect(label).not.toContain('seuil');
      expect(label).toContain('m');
    });

    it('falls back to the neighbour id when it has no name', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { nom: '', latitude: 47.102, longitude: 1.5 })
      ]);

      expect(page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }))).toContain('halle');
    });
  });

  it('exposes the editing lock as the job service sees it, not as its own copy', () => {
    const page = createPage();

    expect(page.editingLocked()).toBe(false);
  });

  it('keeps a checkbox column and an actions column around the data ones', () => {
    const page = createPage();

    expect(page.columns[0]).toBe('select');
    expect(page.columns.at(-1)).toBe('actions');
  });
});
