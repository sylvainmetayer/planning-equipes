// The apply-calendar dialog: it reads the preview it was handed, never
// previews itself, and writes through the generator API on confirmation.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GenerateurCreneauxApi } from '../../core/api/generateur-creneaux-api';
import { RapportApplication } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { GabaritsApplicationDialog } from './gabarits-application-dialog';

const APERCU: RapportApplication = {
  conserves: 4,
  misAJour: 1,
  crees: 2,
  supprimes: 1,
  creneauxSupprimes: [],
  supprimesAvecPostes: [
    { id: 7, jour: 1, date: '2027-07-12', heureDebut: '09:00:00', heureFin: '12:00:00' },
  ],
  postesSupprimes: 3,
  datesEnEcart: [],
  aucunChangement: false,
  controle: {
    nombreCreneaux: 6,
    anomalies: [
      { severite: 'ERREUR', type: 'DOUBLON', message: 'Trou', date: '2027-07-12' },
      { severite: 'AVERTISSEMENT', type: 'DOUBLON', message: 'Court', date: null },
    ],
    ouvertures: [],
    faisabilite: null,
  },
};

describe('GabaritsApplicationDialog', () => {
  const apply = vi.fn();
  const close = vi.fn();
  const reportError = vi.fn();
  const locked = signal(false);

  function monter(apercu: RapportApplication) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: GenerateurCreneauxApi, useValue: { apply } },
        { provide: ReferenceCrudService, useValue: { reportError } },
        { provide: SolverJobService, useValue: { editingLocked: locked } },
        { provide: MatDialogRef, useValue: { close } },
        { provide: MAT_DIALOG_DATA, useValue: { apercu } },
      ],
    });
    const fixture = TestBed.createComponent(GabaritsApplicationDialog);
    return {
      fixture,
      dialog: fixture.componentInstance,
      racine: fixture.nativeElement as HTMLElement,
    };
  }

  beforeEach(() => {
    apply.mockReset();
    close.mockReset();
    reportError.mockReset();
    locked.set(false);
  });

  it('says what the write does, and which removals take seats with them', async () => {
    const { fixture, racine } = monter(APERCU);
    await fixture.whenStable();

    expect(racine.querySelectorAll('.gabarits-bilan li')).toHaveLength(4);
    expect(racine.querySelector('.gabarits-suppression')).not.toBeNull();
    expect(racine.querySelector('[role="alert"]')!.textContent).toContain('3');
    expect(racine.querySelectorAll('.gabarits-supprimes li')).toHaveLength(1);
    expect(racine.querySelectorAll('.serie-anomalies li')).toHaveLength(2);
    expect(racine.querySelector('.serie-bilan')!.textContent).toContain('1 erreur');
  });

  it('shows nothing to change and refuses to apply', async () => {
    const { fixture, dialog, racine } = monter({ ...APERCU, aucunChangement: true });
    await fixture.whenStable();

    expect(racine.querySelector('.gabarits-bilan')).toBeNull();
    await dialog['appliquer']();
    expect(apply).not.toHaveBeenCalled();
  });

  it('applies the calendar and closes with the report', async () => {
    const rapport = { ...APERCU, crees: 2 };
    apply.mockResolvedValue({ source: 'GABARITS', application: rapport });
    const { dialog } = monter(APERCU);

    await dialog['appliquer']();

    expect(apply).toHaveBeenCalledExactlyOnceWith({ source: 'GABARITS' });
    expect(close).toHaveBeenCalledExactlyOnceWith(rapport);
    expect(dialog['application']()).toBe(false);
  });

  it('closes with null when the answer carries no report', async () => {
    apply.mockResolvedValue({ source: 'GABARITS' });
    const { dialog } = monter(APERCU);

    await dialog['appliquer']();

    expect(close).toHaveBeenCalledExactlyOnceWith(null);
  });

  it('reports a failed write and stays open', async () => {
    apply.mockRejectedValue(new Error('boom'));
    const { dialog } = monter(APERCU);

    await dialog['appliquer']();

    expect(reportError).toHaveBeenCalledOnce();
    expect(close).not.toHaveBeenCalled();
    expect(dialog['application']()).toBe(false);
  });

  it('does not write while a solve holds the edition', async () => {
    locked.set(true);
    const { dialog } = monter(APERCU);

    await dialog['appliquer']();

    expect(apply).not.toHaveBeenCalled();
  });
});
