import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ContrainteAdHoc } from '../../core/models';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { seedStore } from '../../core/testing/seed-store';
import { AdHocConstraintsPage } from './ad-hoc-constraints-page';

function contrainte(id: string): ContrainteAdHoc {
  return {
    id,
    type: 'AFFECTATION_FORCEE',
    animateursConcernes: [{ id: 'A1' }],
    creneau: null,
    stand: { id: 'S1' },
    raison: 'test',
  };
}

describe('AdHocConstraintsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = { reload: vi.fn(async () => undefined), remove: vi.fn(async () => true) };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };

  beforeEach(() => {
    crud.reload.mockClear();
    dialog.open.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ReferenceCrudService, useValue: crud },
        { provide: MatDialog, useValue: dialog },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked: () => false },
        },
        {
          provide: ProblemesStore,
          useValue: {
            reloadFeasibility: vi.fn(async () => undefined),
            causeParContrainteAdHocId: signal(new Map()),
          },
        },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(contraintes: ContrainteAdHoc[]): void {
    seedStore(referenceData, 'contraintes', contraintes);
    TestBed.createComponent(AdHocConstraintsPage);
  }

  /** `?edit=<id>`: « Voir la fiche » on an adjustment saved with a warning lands here with its form open. */
  describe('the edit deep link', () => {
    it('opens the form of the adjustment named in the URL once the référentiel is in', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=AH2');
      createPage([contrainte('AH1'), contrainte('AH2')]);
      await Promise.resolve();

      expect(dialog.open).toHaveBeenCalledOnce();
      const [, config] = dialog.open.mock.calls[0] as unknown as [
        unknown,
        { data: { contrainte: ContrainteAdHoc } },
      ];
      expect(config.data.contrainte.id).toBe('AH2');
    });

    it('opens nothing for an adjustment the référentiel does not hold', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=AH9');
      createPage([contrainte('AH1')]);
      await Promise.resolve();

      expect(dialog.open).not.toHaveBeenCalled();
    });
  });
});
