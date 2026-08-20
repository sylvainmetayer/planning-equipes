// Multi-selection logic only: the component is created but never rendered, so
// this stays a logic test (the project favours those over full DOM rendering).
// The ninja picker moved to the Paramètres page, and its tests with it.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { TypologiesPage } from './typologies-page';
import type { TypologieItem } from '../../core/models';

/** Reaches the protected members the template binds to. */
type PageInternals = {
  selection: TableSelection<string>;
  removeSelection: () => Promise<void>;
};

describe('TypologiesPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    save: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0)
  };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.save.mockClear();
    crud.removeMany.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn() } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: { open: vi.fn() } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(typologies: TypologieItem[]): PageInternals {
    referenceData.typologies.set(typologies);
    return TestBed.createComponent(TypologiesPage).componentInstance as unknown as PageInternals;
  }

  describe('multi-selection', () => {
    it('supprime exactement les lignes cochées', async () => {
      const page = createPage([
        { id: 'STRATEGIE', label: 'Stratégie' },
        { id: 'JOKER', label: 'Joker' },
        { id: 'AMBIANCE', label: 'Ambiance' }
      ]);

      page.selection.toggle('STRATEGIE');
      page.selection.toggle('AMBIANCE');
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('typologies', ['STRATEGIE', 'AMBIANCE'], expect.anything());
    });

    // Le référentiel est rechargé après chaque écriture : une ligne disparue
    // ne doit plus peser sur la sélection.
    it('oublie une typologie supprimée entre-temps', () => {
      const page = createPage([
        { id: 'STRATEGIE', label: 'Stratégie' },
        { id: 'JOKER', label: 'Joker' }
      ]);
      page.selection.toggleAll();

      referenceData.typologies.set([{ id: 'JOKER', label: 'Joker' }]);

      expect(page.selection.selectedIds()).toEqual(['JOKER']);
    });
  });
});
