// Bulk edit of the selected emplacements: put them all on one GPS point, or
// clear coordinates imported wrong. The rules are unit-tested next door
// (`emplacement-bulk-edit.ts`); what only exists here is the coupling between
// the map and the "Action" select — a click on the map must switch the mode to
// « Définir » by itself, or the user picks a point and the apply button stays
// dead with no explanation.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement } from '../../core/models';
import { MapPicker } from '../../shared/map-picker';
import { EmplacementBulkEditDialog } from './emplacement-bulk-edit-dialog';

const EMPLACEMENTS: Emplacement[] = [
  { id: 'hall', nom: 'Hall A', latitude: 47.2, longitude: -1.55 },
  { id: 'salle', nom: 'Salle B', latitude: null, longitude: null }
];

function monter(emplacements: Emplacement[], options: { editingLocked?: boolean; saved?: number } = {}) {
  const saveMany = vi.fn(async () => options.saved ?? emplacements.length);
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: SolverJobService, useValue: { editingLocked: signal(options.editingLocked ?? false) } },
      { provide: ReferenceCrudService, useValue: { saveMany } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { emplacements } }
    ]
  });
  return { fixture: TestBed.createComponent(EmplacementBulkEditDialog), saveMany, close };
}

function racine(fixture: ComponentFixture<EmplacementBulkEditDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function bouton(fixture: ComponentFixture<EmplacementBulkEditDialog>): HTMLButtonElement {
  return racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;
}

function soumettre(fixture: ComponentFixture<EmplacementBulkEditDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

/** Picks an option of a `mat-select` the way a user does: open, then click. */
async function choisir(
  fixture: ComponentFixture<EmplacementBulkEditDialog>,
  name: string,
  libelle: string
): Promise<void> {
  const select = racine(fixture).querySelector(`mat-select[name="${name}"]`) as HTMLElement;
  (select.querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
  await fixture.whenStable();
  const option = Array.from(document.querySelectorAll('mat-option')).find(
    (each) => each.textContent!.trim() === libelle
  );
  expect(option, `option « ${libelle} » absente`).toBeDefined();
  (option as HTMLElement).click();
  await fixture.whenStable();
}

function cliquerSurLaCarte(
  fixture: ComponentFixture<EmplacementBulkEditDialog>,
  latitude: number,
  longitude: number
): void {
  const picker = fixture.debugElement.query(By.directive(MapPicker)).componentInstance as MapPicker;
  picker.positionChange.emit({ latitude, longitude });
}

/** Visible text of the "Action" select. */
function modeAffiche(fixture: ComponentFixture<EmplacementBulkEditDialog>): string {
  return racine(fixture).querySelector('mat-select[name="coordonneesMode"] .mat-mdc-select-value')!.textContent!.trim();
}

describe('EmplacementBulkEditDialog', () => {
  it('says how many emplacements the edit is about and changes nothing by default', async () => {
    const { fixture } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe('Modifier 2 emplacements');
    expect(modeAffiche(fixture)).toBe('Ne pas modifier');
    expect(bouton(fixture).disabled).toBe(true);
  });

  it('keeps the coordinate fields inert until the user asks to set a point', async () => {
    const { fixture } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    expect((racine(fixture).querySelector('input[name="latitude"]') as HTMLInputElement).disabled).toBe(true);

    await choisir(fixture, 'coordonneesMode', 'Définir');

    expect((racine(fixture).querySelector('input[name="latitude"]') as HTMLInputElement).disabled).toBe(false);
  });

  it('switches the action to « Définir » by itself when the user clicks the map', async () => {
    const { fixture } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    cliquerSurLaCarte(fixture, 47.21725, -1.553621);
    await fixture.whenStable();

    // Otherwise the point is picked and the apply button stays dead.
    expect(modeAffiche(fixture)).toBe('Définir');
    expect((racine(fixture).querySelector('input[name="latitude"]') as HTMLInputElement).value).toBe('47.21725');
    expect(bouton(fixture).disabled).toBe(false);
  });

  it('writes the same point on every selected emplacement', async () => {
    const { fixture, saveMany, close } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    cliquerSurLaCarte(fixture, 47.2, -1.55);
    await fixture.whenStable();
    soumettre(fixture);
    await fixture.whenStable();

    const [resource, payloads] = saveMany.mock.calls[0] as unknown as [string, Emplacement[]];
    expect(resource).toBe('emplacements');
    expect(payloads.map((each) => [each.id, each.latitude, each.longitude])).toEqual([
      ['hall', 47.2, -1.55],
      ['salle', 47.2, -1.55]
    ]);
    // Names are per-place and must survive a coordinate batch.
    expect(payloads.map((each) => each.nom)).toEqual(['Hall A', 'Salle B']);
    expect(close).toHaveBeenCalledWith(true);
  });

  it('clears every coordinate on « Effacer », and hides the map that has nothing to show', async () => {
    const { fixture, saveMany } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    await choisir(fixture, 'coordonneesMode', 'Effacer');

    expect(fixture.debugElement.query(By.directive(MapPicker))).toBeNull();
    expect(bouton(fixture).disabled).toBe(false);

    soumettre(fixture);
    await fixture.whenStable();

    const payloads = (saveMany.mock.calls[0] as unknown as [string, Emplacement[]])[1];
    expect(payloads.every((each) => each.latitude === null && each.longitude === null)).toBe(true);
  });

  it('refuses a half-filled point rather than writing one coordinate', async () => {
    const { fixture, saveMany } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    await choisir(fixture, 'coordonneesMode', 'Définir');
    const latitude = racine(fixture).querySelector('input[name="latitude"]') as HTMLInputElement;
    latitude.value = '47.2';
    latitude.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(bouton(fixture).disabled).toBe(true);
    soumettre(fixture);
    await fixture.whenStable();
    expect(saveMany).not.toHaveBeenCalled();
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = monter(EMPLACEMENTS, { editingLocked: true });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect((fixture.debugElement.query(By.directive(MapPicker)).componentInstance as MapPicker).disabled()).toBe(true);
  });

  it('cancels without writing anything', async () => {
    const { fixture, saveMany, close } = monter(EMPLACEMENTS);
    await fixture.whenStable();

    (racine(fixture).querySelector('mat-dialog-actions button') as HTMLButtonElement).click();

    expect(saveMany).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledWith(false);
  });
});
