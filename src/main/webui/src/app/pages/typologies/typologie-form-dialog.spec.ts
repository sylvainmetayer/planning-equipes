// The smallest form of the application, and the one carrying a field the user
// never sees: `ninja`. Only one typologie may hold it, it is set from a
// dedicated select on the list page, and a PUT replaces the whole row — so a
// dialog that forgot to carry it over would silently clear it on any edit.
//
// Rendering rather than calling `save()` directly, for the same reason as
// `stand-form-dialog.spec.ts`: an `ngModel` with no `name` inside a `<form>`
// throws NG01352 and costs the field its label and its accessible name.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { TypologieItem } from '../../core/models';
import { TypologieFormDialog } from './typologie-form-dialog';

function monter(
  typologie: TypologieItem | null,
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
      { provide: MAT_DIALOG_DATA, useValue: { typologie } },
    ],
  });
  return { fixture: TestBed.createComponent(TypologieFormDialog), save, close };
}

function racine(fixture: ComponentFixture<TypologieFormDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** The control by its name, whichever tag it is: the description is a textarea. */
function champ(
  fixture: ComponentFixture<TypologieFormDialog>,
  name: string,
): HTMLInputElement | HTMLTextAreaElement {
  return racine(fixture).querySelector(`input[name="${name}"], textarea[name="${name}"]`) as
    HTMLInputElement | HTMLTextAreaElement;
}

function saisir(
  fixture: ComponentFixture<TypologieFormDialog>,
  name: string,
  valeur: string,
): void {
  const input = champ(fixture, name);
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

function submit(fixture: ComponentFixture<TypologieFormDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

function nomsEnregistres(fixture: ComponentFixture<TypologieFormDialog>): string[] {
  const ngForm = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
  return Object.keys(ngForm.controls);
}

describe('TypologieFormDialog', () => {
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

    expect(nomsEnregistres(fixture).sort((a, b) => a.localeCompare(b))).toEqual([
      'code',
      'description',
      'label',
      'maxCreneauxParAnimateur',
    ]);
    expect(erreursConsole.filter((args) => JSON.stringify(args).includes('NG01352'))).toEqual([]);
  });

  it('titles itself after the typologie it was opened on', async () => {
    const { fixture } = monter({ id: 'ambiance', label: 'Ambiance', ninja: false });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe(
      'Modifier la typologie Ambiance',
    );
    expect(racine(fixture).querySelector('button[type="submit"]')!.textContent!).toContain(
      'Modifier la typologie',
    );
  });

  it('announces a creation when opened on nothing', async () => {
    const { fixture } = monter(null);
    await fixture.whenStable();

    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe('Nouvelle typologie');
    expect(racine(fixture).querySelector('button[type="submit"]')!.textContent!).toContain(
      'Créer la typologie',
    );
  });

  it('shows the drawn identifier read-only on an edit, and asks none on a creation', async () => {
    const { fixture } = monter({ id: 'T3', label: 'Ambiance', ninja: false });
    await fixture.whenStable();
    expect((champ(fixture, 'id') as HTMLInputElement).readOnly).toBe(true);
    expect(champ(fixture, 'id').value).toBe('T3');

    const { fixture: nouveau } = monter(null);
    await nouveau.whenStable();
    expect(champ(nouveau, 'id')).toBeNull();
  });

  it('creates without an id, and sends the code trimmed or null when left blank', async () => {
    const { fixture, save } = monter(null);
    await fixture.whenStable();

    saisir(fixture, 'label', 'Ambiance');
    saisir(fixture, 'code', '  AMBIANCE  ');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();
    const [, payload, editingId] = save.mock.calls[0] as unknown as [
      string,
      TypologieItem,
      string | null,
    ];
    expect(payload.id).toBe('');
    expect(payload.code).toBe('AMBIANCE');
    expect(editingId).toBeNull();

    save.mockClear();
    saisir(fixture, 'code', '   ');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();
    expect((save.mock.calls[0] as unknown as [string, TypologieItem])[1].code).toBeNull();
  });

  it('fills the form from the typologie and saves the edited values, trimmed', async () => {
    const { fixture, save, close } = monter({ id: 'ambiance', label: 'Ambiance', ninja: false });
    await fixture.whenStable();
    expect(champ(fixture, 'label').value).toBe('Ambiance');

    saisir(fixture, 'label', '  Ambiance festive  ');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    expect(save).toHaveBeenCalledOnce();
    const [resource, payload, editingId] = save.mock.calls[0] as unknown as [
      string,
      TypologieItem,
      string | null,
    ];
    expect(resource).toBe('typologies');
    expect(payload).toEqual({
      id: 'ambiance',
      code: null,
      label: 'Ambiance festive',
      ninja: false,
      maxCreneauxParAnimateur: null,
      description: null,
      modifieLe: null,
    });
    expect(editingId).toBe('ambiance');
    expect(close).toHaveBeenCalledWith(true);
  });

  /** Issue #594: the cap is optional, and an empty field means « no cap », never zero. */
  it('saves the créneau cap it was given, and null when the field is left empty', async () => {
    const { fixture, save } = monter({
      id: 'hommes-jeu',
      label: 'Hommes jeu',
      ninja: false,
      maxCreneauxParAnimateur: 4,
    });
    await fixture.whenStable();
    expect(champ(fixture, 'maxCreneauxParAnimateur').value).toBe('4');

    saisir(fixture, 'maxCreneauxParAnimateur', '6');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    expect(
      (save.mock.calls[0] as unknown as [string, TypologieItem])[1].maxCreneauxParAnimateur,
    ).toBe(6);

    save.mockClear();
    saisir(fixture, 'maxCreneauxParAnimateur', '');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    expect(
      (save.mock.calls[0] as unknown as [string, TypologieItem])[1].maxCreneauxParAnimateur,
    ).toBe(null);
  });

  /**
   * The organiser's own note on the typologie — « nécessite d'apprendre 45
   * jeux ». A blank box is no note at all, not an empty string: the screens
   * would otherwise have to test for both.
   */
  it('saves the description it was given, and null when the box is blank', async () => {
    const { fixture, save } = monter({
      id: 'strategie',
      label: 'Stratégie',
      ninja: false,
      description: "Nécessite d'apprendre 45 jeux",
    });
    await fixture.whenStable();
    expect(champ(fixture, 'description').value).toBe("Nécessite d'apprendre 45 jeux");

    saisir(fixture, 'description', '  Trois soirées de formation  ');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();
    expect((save.mock.calls[0] as unknown as [string, TypologieItem])[1].description).toBe(
      'Trois soirées de formation',
    );

    save.mockClear();
    saisir(fixture, 'description', '   ');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();
    expect((save.mock.calls[0] as unknown as [string, TypologieItem])[1].description).toBe(null);
  });

  it('carries the ninja flag over untouched, since a PUT replaces the whole row', async () => {
    const { fixture, save } = monter({ id: 'ninja', label: 'Ninja', ninja: true });
    await fixture.whenStable();

    saisir(fixture, 'label', 'Ninja warrior');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    // The flag is invisible in this dialog: dropping it would silently move the
    // ninja typologie to nothing on the next label fix.
    expect((save.mock.calls[0] as unknown as [string, TypologieItem])[1].ninja).toBe(true);
  });

  it('keeps the dialog open when the save is refused', async () => {
    const { fixture, close } = monter(
      { id: 'ambiance', label: 'Ambiance', ninja: false },
      { saveOk: false },
    );
    await fixture.whenStable();

    submit(fixture);
    await fixture.whenStable();

    expect(close).not.toHaveBeenCalled();
  });

  it('cancels without saving', async () => {
    const { fixture, save, close } = monter(null);
    await fixture.whenStable();

    (racine(fixture).querySelector('mat-dialog-actions button') as HTMLButtonElement).click();

    expect(save).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledWith(false);
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = monter(
      { id: 'ambiance', label: 'Ambiance', ninja: false },
      { editingLocked: true },
    );
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(
      (racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement).disabled,
    ).toBe(true);
  });
});
