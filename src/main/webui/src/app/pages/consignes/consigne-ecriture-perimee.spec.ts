// A consigne written by another session since the form opened (issue #362):
// the form sends the stamp it loaded as the precondition of its date, and a
// 409 is answered with the usual choice — overwrite sends the same request
// again without its precondition, reload writes nothing.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { ConsignesApi } from '../../core/api/consignes-api';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../../core/api.service';
import { ConsignesStore } from '../../core/consignes.store';
import { ConsigneEdition } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { fakeDialogRef, noDraftStorage } from '../../core/testing/brouillon';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ConsigneFormDialog } from './consigne-form-dialog';

const CONSIGNE: ConsigneEdition = {
  date: '2026-07-11',
  fermetureDebut: '12:00:00',
  fermetureFin: '16:00:00',
  motif: 'Arrêté préfectoral',
  prereglage: null,
  fenetres: [],
  ouvertures: [],
  repas: null,
  creneauxAjoutes: [],
  creeLe: '2026-07-01T08:00:00Z',
  modifieLe: '2026-07-02T09:00:00.123Z',
};

const STALE = new ApiError(
  409,
  'conflict',
  'Cette consigne a été modifiée par une autre session.',
  CODE_MODIFICATION_CONCURRENTE,
  '2026-07-03T10:00:00Z',
);

describe('ConsigneFormDialog — a stale write', () => {
  let dialog: ConsigneFormDialog;
  let api: { preselection: ReturnType<typeof vi.fn>; poser: ReturnType<typeof vi.fn> };
  let confirm: { askThreeWay: ReturnType<typeof vi.fn> };
  let store: { reload: ReturnType<typeof vi.fn> };
  let close: Mock<(result?: unknown) => void>;

  beforeEach(() => {
    api = {
      preselection: vi.fn(() =>
        Promise.resolve({ date: CONSIGNE.date, creneauxDuJour: 2, stands: [] }),
      ),
      poser: vi.fn().mockRejectedValueOnce(STALE).mockResolvedValue([]),
    };
    confirm = { askThreeWay: vi.fn() };
    store = { reload: vi.fn(() => Promise.resolve()) };
    close = vi.fn<(result?: unknown) => void>();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ConsignesApi, useValue: api },
        { provide: ConfirmService, useValue: confirm },
        { provide: ConsignesStore, useValue: store },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: MatDialogRef, useValue: fakeDialogRef(close) },
        ...noDraftStorage(),
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            mode: 'modifier',
            consigne: CONSIGNE,
            datesInitiales: [CONSIGNE.date],
            datesCandidates: [CONSIGNE.date],
            prereglages: [],
            stands: [],
            typologies: [],
            emplacements: [],
          },
        },
      ],
    });
    dialog = TestBed.createComponent(ConsigneFormDialog).componentInstance;
  });

  it('sends the stamp it loaded for the date it opened on', async () => {
    confirm.askThreeWay.mockResolvedValue(null);
    await dialog['save']();
    expect(api.poser).toHaveBeenCalledTimes(1);
    expect(api.poser.mock.calls[0][0].modifieLe).toEqual({ [CONSIGNE.date]: CONSIGNE.modifieLe });
  });

  it('sends the same request again without the precondition on « Écraser quand même »', async () => {
    confirm.askThreeWay.mockResolvedValue(true);
    await dialog['save']();
    expect(api.poser).toHaveBeenCalledTimes(2);
    expect(api.poser.mock.calls[1][0].modifieLe).toBeNull();
    expect(close).toHaveBeenCalledWith([]);
  });

  it('writes nothing and reloads on « Recharger »', async () => {
    confirm.askThreeWay.mockResolvedValue(false);
    await dialog['save']();
    expect(api.poser).toHaveBeenCalledTimes(1);
    expect(store.reload).toHaveBeenCalledTimes(1);
    expect(close).toHaveBeenCalledWith(null);
  });

  it('keeps the form as typed when the choice is dismissed', async () => {
    confirm.askThreeWay.mockResolvedValue(null);
    await dialog['save']();
    expect(api.poser).toHaveBeenCalledTimes(1);
    expect(store.reload).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();
  });
});
