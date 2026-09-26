// The guided creation's two writes — the stand, then its hours — and what a
// failure between them leaves: the stand kept, a retry sending the hours
// alone, never a second stand, and closing onto the new stand's fiche.

import { provideZonelessChangeDetection, signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StandsApi } from '../../core/api/stands-api';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { StandCreationDialog } from './stand-creation-dialog';
import { CreationWeekday, CreationWindow } from './stand-creation';

type DialogInternals = {
  nom: WritableSignal<string>;
  typologies: WritableSignal<string[]>;
  windows: WritableSignal<CreationWindow[]>;
  weekdays: WritableSignal<CreationWeekday[]>;
  createdId: () => string | null;
  hoursError: () => string;
  create: () => Promise<void>;
  cancel: () => void;
};

describe('StandCreationDialog', () => {
  const store = {
    typologies: signal([{ id: 'T1', label: 'Enfance' }]),
    emplacements: signal([]),
    stands: signal([]),
    save: vi.fn(async () => ({ id: 'S9' })),
    reload: vi.fn(async () => undefined),
  };
  const standsApi = {
    openings: vi.fn(async () => ({
      jours: [],
      stands: [],
      standsJamaisOuverts: 0,
      postesTotal: 0,
      anomalies: [],
    })),
    saveOpeningsGrid: vi.fn(),
  };
  const crud = { reportError: vi.fn() };
  const dialogRef = { close: vi.fn() };

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: ReferenceDataStore, useValue: store },
        { provide: StandsApi, useValue: standsApi },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      ],
    });
  });

  /** A stand of three on every window, where the first step said one: a grid to write. */
  function filled(): DialogInternals {
    const dialog = TestBed.createComponent(StandCreationDialog)
      .componentInstance as unknown as DialogInternals;
    dialog.nom.set('Loup-Garou');
    dialog.typologies.set(['T1']);
    dialog.windows.set([{ key: '10:00-12:00', label: '10:00–12:00', open: true, effectif: 3 }]);
    dialog.weekdays.set([{ day: 6, open: true }]);
    return dialog;
  }

  it('keeps the stand when its hours fail, and a retry sends the hours alone', async () => {
    standsApi.saveOpeningsGrid.mockRejectedValueOnce(new Error('grille refusée'));
    standsApi.saveOpeningsGrid.mockResolvedValueOnce({ stands: [] });
    const dialog = filled();

    await dialog.create();
    expect(store.save).toHaveBeenCalledOnce();
    expect(crud.reportError).toHaveBeenCalled();
    expect(dialog.createdId()).toBe('S9');
    expect(dialog.hoursError()).toContain("ses horaires n'ont pas été enregistrés");
    expect(dialogRef.close).not.toHaveBeenCalled();

    await dialog.create();
    expect(store.save).toHaveBeenCalledOnce();
    expect(standsApi.saveOpeningsGrid).toHaveBeenCalledTimes(2);
    expect(dialogRef.close).toHaveBeenCalledWith('S9');
  });

  it('closes onto the new stand when cancelled after its hours failed', async () => {
    standsApi.saveOpeningsGrid.mockRejectedValueOnce(new Error('grille refusée'));
    const dialog = filled();

    await dialog.create();
    dialog.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith('S9');
  });

  it('writes no grid when the hours were left as laid', async () => {
    const dialog = filled();
    dialog.windows.set([{ key: '10:00-12:00', label: '10:00–12:00', open: true, effectif: 1 }]);

    await dialog.create();

    expect(standsApi.saveOpeningsGrid).not.toHaveBeenCalled();
    expect(dialogRef.close).toHaveBeenCalledWith('S9');
  });
});
