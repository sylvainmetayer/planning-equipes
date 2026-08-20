// Ninja picker logic only (moved here with the picker itself): the component
// is created but never rendered, so this stays a logic test (the project
// favours those over full DOM rendering).

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { ParametresPage } from './parametres-page';
import type { TypologieItem } from '../../core/models';

/** Reaches the protected members the template binds to. */
type PageInternals = {
  typologieNinjaId: Signal<string | null>;
  alerteNinjaManquant: Signal<string>;
  setNinja: (id: string | null) => Promise<void>;
};

describe('ParametresPage ninja picker', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    save: vi.fn(async () => true),
    reportError: vi.fn()
  };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.save.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        // The constructor loads the scenario list and the découpage
        // parameters; both answers are irrelevant to the picker under test.
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: EditionStore, useValue: { courant: () => null } },
        { provide: ProblemesStore, useValue: { report: () => null, reloadFeasibility: vi.fn(async () => undefined) } },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false, activeJobDescription: () => '' } },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: PlanSnapshotStore, useValue: { capturer: vi.fn(async () => undefined) } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn(async () => undefined) } },
        { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(typologies: TypologieItem[]): PageInternals {
    referenceData.typologies.set(typologies);
    return TestBed.createComponent(ParametresPage).componentInstance as unknown as PageInternals;
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

  it('warns when the referential has typologies but no ninja', () => {
    const page = createPage([{ id: 'STRATEGIE', label: 'Stratégie' }]);
    expect(page.alerteNinjaManquant()).not.toBe('');
  });

  it('stays silent on an empty referential and once a ninja is designated', () => {
    expect(createPage([]).alerteNinjaManquant()).toBe('');
    expect(createPage([{ id: 'JOKER', label: 'Joker', ninja: true }]).alerteNinjaManquant()).toBe('');
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
});
