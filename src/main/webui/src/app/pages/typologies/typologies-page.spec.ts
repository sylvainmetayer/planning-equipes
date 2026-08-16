// Ninja picker logic only: the component is created but never rendered, so this
// stays a logic test (the project favours those over full DOM rendering).

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
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
  typologieNinjaId: Signal<string | null>;
  setNinja: (id: string | null) => Promise<void>;
  selection: TableSelection<string>;
  removeSelection: () => Promise<void>;
};

describe('TypologiesPage ninja picker', () => {
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
        { provide: ApiService, useValue: { get: vi.fn() } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false } },
        { provide: MatDialog, useValue: { open: vi.fn() } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(typologies: TypologieItem[]): PageInternals {
    referenceData.typologies.set(typologies);
    return TestBed.createComponent(TypologiesPage).componentInstance as unknown as PageInternals;
  }

  it('reads the current ninja typologie from the store', () => {
    const page = createPage([
      { id: 'STRATEGIE', label: 'Stratégie' },
      { id: 'JOKER', label: 'Joker', ninja: true }
    ]);
    expect(page.typologieNinjaId()).toBe('JOKER');
  });

  it('reports no ninja when the referential has none', () => {
    const page = createPage([{ id: 'STRATEGIE', label: 'Stratégie' }]);
    expect(page.typologieNinjaId()).toBeNull();
  });

  it('promotes the selected typologie, letting the server demote the previous one', async () => {
    const page = createPage([
      { id: 'STRATEGIE', label: 'Stratégie' },
      { id: 'JOKER', label: 'Joker', ninja: true }
    ]);

    await page.setNinja('STRATEGIE');

    expect(crud.save).toHaveBeenCalledTimes(1);
    expect(crud.save).toHaveBeenCalledWith(
      'typologies',
      { id: 'STRATEGIE', label: 'Stratégie', ninja: true },
      'STRATEGIE',
      expect.anything()
    );
  });

  it('clears the flag on the current holder when "Aucune" is picked', async () => {
    const page = createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]);

    await page.setNinja(null);

    expect(crud.save).toHaveBeenCalledWith(
      'typologies',
      { id: 'JOKER', label: 'Joker', ninja: false },
      'JOKER',
      expect.anything()
    );
  });

  it('does nothing when the selection did not change', async () => {
    const page = createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]);

    await page.setNinja('JOKER');

    expect(crud.save).not.toHaveBeenCalled();
  });

  it('does nothing when clearing a referential that has no ninja', async () => {
    const page = createPage([{ id: 'STRATEGIE', label: 'Stratégie' }]);

    await page.setNinja(null);

    expect(crud.save).not.toHaveBeenCalled();
  });

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
