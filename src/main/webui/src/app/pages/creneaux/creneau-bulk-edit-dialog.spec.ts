// Bulk edit of the selected créneaux — the dialog used to shift a whole day's
// hours at once. The rules live next door in `creneau-bulk-edit.ts` and are
// unit-tested there; what only exists here is the gate: an empty patch, or a
// patch that would leave a créneau ending before it starts, must not reach the
// server, and the user must be told why the button is dead.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';
import { CreneauBulkEditDialog } from './creneau-bulk-edit-dialog';

const CRENEAUX: Creneau[] = [
  { id: 1, jour: 1, date: '2026-07-14', heureDebut: '10:00', heureFin: '12:00' },
  { id: 2, jour: 1, date: '2026-07-14', heureDebut: '14:00', heureFin: '19:00' }
];

function monter(creneaux: Creneau[], options: { editingLocked?: boolean; saved?: number } = {}) {
  const saveMany = vi.fn(async () => options.saved ?? creneaux.length);
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ReferenceDataStore, useValue: { creneaux: signal(creneaux) } },
      { provide: SolverJobService, useValue: { editingLocked: signal(options.editingLocked ?? false) } },
      { provide: ReferenceCrudService, useValue: { saveMany } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { creneaux } }
    ]
  });
  return { fixture: TestBed.createComponent(CreneauBulkEditDialog), saveMany, close };
}

function racine(fixture: ComponentFixture<CreneauBulkEditDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function saisir(fixture: ComponentFixture<CreneauBulkEditDialog>, name: string, valeur: string): void {
  const input = racine(fixture).querySelector(`input[name="${name}"]`) as HTMLInputElement;
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

function soumettre(fixture: ComponentFixture<CreneauBulkEditDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

function bouton(fixture: ComponentFixture<CreneauBulkEditDialog>): HTMLButtonElement {
  return racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;
}

describe('CreneauBulkEditDialog', () => {
  it('says how many créneaux the edit is about', async () => {
    const { fixture } = monter(CRENEAUX);
    await fixture.whenStable();

    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe('Modifier 2 créneaux');
  });

  it('refuses to submit an untouched form', async () => {
    const { fixture, saveMany } = monter(CRENEAUX);
    await fixture.whenStable();

    expect(bouton(fixture).disabled).toBe(true);
    // Even forced through, an empty patch must write nothing.
    soumettre(fixture);
    await fixture.whenStable();
    expect(saveMany).not.toHaveBeenCalled();
  });

  it('applies only the field the user filled, leaving the other one per créneau', async () => {
    const { fixture, saveMany, close } = monter(CRENEAUX);
    await fixture.whenStable();

    saisir(fixture, 'heureDebut', '09:00');
    await fixture.whenStable();
    expect(bouton(fixture).disabled).toBe(false);

    soumettre(fixture);
    await fixture.whenStable();

    const [resource, payloads] = saveMany.mock.calls[0] as unknown as [string, Creneau[]];
    expect(resource).toBe('creneaux');
    expect(payloads.map((creneau) => [creneau.id, creneau.heureDebut, creneau.heureFin])).toEqual([
      [1, '09:00', '12:00'],
      [2, '09:00', '19:00']
    ]);
    expect(close).toHaveBeenCalledWith(true);
  });

  // Un lot qui fait franchir minuit à une partie de la sélection est légitime —
  // c'est ainsi que le domaine écrit une soirée — mais personne ne relit
  // soixante lignes avant de confirmer : le dialogue les compte, et applique.
  it('names the créneaux that would cross midnight, without blocking the batch', async () => {
    const { fixture, saveMany } = monter(CRENEAUX);
    await fixture.whenStable();

    // 13:00 is after the first créneau's 12:00 end but before the second's 19:00.
    saisir(fixture, 'heureDebut', '13:00');
    await fixture.whenStable();

    expect(racine(fixture).textContent).toContain('franchiraient minuit');
    expect(bouton(fixture).disabled).toBe(false);

    soumettre(fixture);
    await fixture.whenStable();
    expect(saveMany).toHaveBeenCalledOnce();
  });

  it('says nothing about midnight while every créneau stays inside its day', async () => {
    const { fixture } = monter(CRENEAUX);
    await fixture.whenStable();

    saisir(fixture, 'heureFin', '20:00');
    await fixture.whenStable();

    expect(racine(fixture).textContent).not.toContain('franchiraient minuit');
  });

  it('keeps the dialog open when the server saved nothing', async () => {
    const { fixture, close } = monter(CRENEAUX, { saved: 0 });
    await fixture.whenStable();

    saisir(fixture, 'heureFin', '20:00');
    await fixture.whenStable();
    soumettre(fixture);
    await fixture.whenStable();

    expect(close).not.toHaveBeenCalled();
  });

  it('never fires two batches for two submits', async () => {
    let resoudre = (_count: number) => undefined as void;
    const saveMany = vi.fn(() => new Promise<number>((resolve) => (resoudre = resolve)));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ReferenceDataStore, useValue: { creneaux: signal(CRENEAUX) } },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: ReferenceCrudService, useValue: { saveMany } },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { creneaux: CRENEAUX } }
      ]
    });
    const fixture = TestBed.createComponent(CreneauBulkEditDialog);
    await fixture.whenStable();

    saisir(fixture, 'heureFin', '20:00');
    await fixture.whenStable();
    soumettre(fixture);
    soumettre(fixture);
    await fixture.whenStable();

    // A double Enter on a fifty-row batch would otherwise write it twice.
    expect(saveMany).toHaveBeenCalledOnce();
    resoudre(2);
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = monter(CRENEAUX, { editingLocked: true });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(bouton(fixture).disabled).toBe(true);
  });

  it('cancels without writing anything', async () => {
    const { fixture, saveMany, close } = monter(CRENEAUX);
    await fixture.whenStable();

    (racine(fixture).querySelector('mat-dialog-actions button') as HTMLButtonElement).click();

    expect(saveMany).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledWith(false);
  });
});
