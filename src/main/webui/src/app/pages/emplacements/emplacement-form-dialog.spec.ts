// The emplacement form is the only one with two ways to fill the same data:
// the two numeric fields, and a click on the Leaflet map next to them. What
// must hold is that they stay one single truth — a click writes the fields, and
// what is saved is what the fields show.
//
// Rendered, so that the map picker is really wired to the form rather than
// mocked into agreeing.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement } from '../../core/models';
import { MapPicker } from '../../shared/map-picker';
import { EmplacementFormDialog } from './emplacement-form-dialog';

function monter(
  emplacement: Emplacement | null,
  options: { editingLocked?: boolean; saveOk?: boolean } = {},
) {
  const save = vi.fn(async () => options.saveOk ?? true);
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ReferenceCrudService, useValue: { save } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { emplacement } },
    ],
  });
  return { fixture: TestBed.createComponent(EmplacementFormDialog), save, close };
}

function racine(fixture: ComponentFixture<EmplacementFormDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function champ(fixture: ComponentFixture<EmplacementFormDialog>, name: string): HTMLInputElement {
  return racine(fixture).querySelector(`input[name="${name}"]`) as HTMLInputElement;
}

function saisir(
  fixture: ComponentFixture<EmplacementFormDialog>,
  name: string,
  valeur: string,
): void {
  const input = champ(fixture, name);
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

function submit(fixture: ComponentFixture<EmplacementFormDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

function payload(save: ReturnType<typeof vi.fn>): Emplacement {
  return (save.mock.calls[0] as unknown as [string, Emplacement])[1];
}

/** Emits the picker's output the way a click on the map does. */
function cliquerSurLaCarte(
  fixture: ComponentFixture<EmplacementFormDialog>,
  latitude: number,
  longitude: number,
): void {
  const picker = fixture.debugElement.query(By.directive(MapPicker)).componentInstance as MapPicker;
  picker.positionChange.emit({ latitude, longitude });
}

const HALL: Emplacement = { id: 'hall', nom: 'Hall A', latitude: 47.2, longitude: -1.55 };

describe('EmplacementFormDialog', () => {
  let erreursConsole: unknown[][];

  beforeEach(() => {
    erreursConsole = [];
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) =>
      erreursConsole.push(args),
    );
  });

  it('names every control, so the labels render and no NG01352 is thrown', async () => {
    const { fixture } = monter(null);
    await fixture.whenStable();

    const ngForm = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
    expect(Object.keys(ngForm.controls).sort((a, b) => a.localeCompare(b))).toEqual([
      'code',
      'latitude',
      'longitude',
      'nom',
    ]);
    expect(erreursConsole.filter((args) => JSON.stringify(args).includes('NG01352'))).toEqual([]);
  });

  it('fills the form from the emplacement, coordinates included', async () => {
    const { fixture } = monter(HALL);
    await fixture.whenStable();

    expect(champ(fixture, 'id').value).toBe('hall');
    expect(champ(fixture, 'nom').value).toBe('Hall A');
    expect(champ(fixture, 'latitude').value).toBe('47.2');
    expect(champ(fixture, 'longitude').value).toBe('-1.55');
    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe(
      "Modifier l'emplacement hall",
    );
  });

  it('shows the drawn identifier read-only on an edit, and asks none on a creation', async () => {
    const { fixture } = monter(HALL);
    await fixture.whenStable();
    expect(champ(fixture, 'id').readOnly).toBe(true);

    const { fixture: nouveau } = monter(null);
    await nouveau.whenStable();
    expect(champ(nouveau, 'id')).toBeNull();
  });

  it('writes a map click into the two coordinate fields and says so out loud', async () => {
    const { fixture } = monter(null);
    await fixture.whenStable();

    cliquerSurLaCarte(fixture, 47.21725, -1.553621);
    await fixture.whenStable();

    expect(champ(fixture, 'latitude').value).toBe('47.21725');
    expect(champ(fixture, 'longitude').value).toBe('-1.553621');
    // The map is not keyboard-reachable: the message is what tells a
    // screen-reader user that the click landed somewhere.
    // The leading text is the status icon's ligature.
    expect(racine(fixture).querySelector('app-status-message')!.textContent!).toContain(
      'Position choisie : 47.21725, -1.55362',
    );
  });

  it('saves the coordinates as numbers, trimming the name and the code', async () => {
    const { fixture, save, close } = monter(null);
    await fixture.whenStable();

    saisir(fixture, 'nom', '  Hall A  ');
    saisir(fixture, 'code', '  HALL  ');
    await fixture.whenStable();
    cliquerSurLaCarte(fixture, 47.2, -1.55);
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    // No id on a creation: the server draws it.
    expect(payload(save)).toEqual({
      id: '',
      code: 'HALL',
      nom: 'Hall A',
      latitude: 47.2,
      longitude: -1.55,
      modifieLe: null,
    });
    expect(close).toHaveBeenCalledWith(true);
  });

  it('saves an emplacement with no coordinates as null, never as an empty string', async () => {
    const { fixture, save } = monter({
      id: 'hall',
      nom: 'Hall A',
      latitude: null,
      longitude: null,
    });
    await fixture.whenStable();

    submit(fixture);
    await fixture.whenStable();

    expect(payload(save).latitude).toBeNull();
    expect(payload(save).longitude).toBeNull();
  });

  it('clears a coordinate back to null when the field is emptied', async () => {
    const { fixture, save } = monter(HALL);
    await fixture.whenStable();

    saisir(fixture, 'latitude', '');
    saisir(fixture, 'longitude', '');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    expect(payload(save).latitude).toBeNull();
    expect(payload(save).longitude).toBeNull();
  });

  it('keeps the dialog open when the save is refused, and closes on cancel', async () => {
    const { fixture, close } = monter(HALL, { saveOk: false });
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();
    expect(close).not.toHaveBeenCalled();

    (racine(fixture).querySelector('mat-dialog-actions button') as HTMLButtonElement).click();
    expect(close).toHaveBeenCalledWith(false);
  });

  it('disables the whole form, the map included, while a solve is running', async () => {
    const { fixture } = monter(HALL, { editingLocked: true });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(
      (
        fixture.debugElement.query(By.directive(MapPicker)).componentInstance as MapPicker
      ).disabled(),
    ).toBe(true);
  });
});
