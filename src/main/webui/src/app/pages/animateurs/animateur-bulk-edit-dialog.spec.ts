// Bulk edit of the selected animateurs — four independent edits in one dialog,
// each defaulting to "ne pas modifier". The rules are unit-tested next door
// (`animateur-bulk-edit.ts`); what only exists here is what the user sees and
// touches: which secondary field an action unlocks, and whether the apply
// button really stays dead until a *complete* edit has been described. A
// half-filled edit that goes through writes on every selected row at once.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../../core/api/editions-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Animateur, EtatGel } from '../../core/models';
import { AnimateurBulkEditDialog } from './animateur-bulk-edit-dialog';

const TYPOLOGIES = [
  { id: 'ambiance', label: 'Ambiance' },
  { id: 'expert', label: 'Expert' },
];

const ANIMATEURS: Animateur[] = [
  {
    id: 'a1',
    prenom: 'Amélie',
    nom: 'Nothomb',
    dateNaissance: '1990-05-04',
    manager: false,
    competences: { ambiance: 'DEBUTANT' },
    souhaits: ['expert'],
    joursIndisponibles: [],
  },
  {
    id: 'a2',
    prenom: 'Marcel',
    nom: 'Proust',
    dateNaissance: '1985-07-10',
    manager: true,
    competences: {},
    souhaits: [],
    joursIndisponibles: ['2026-07-14'],
  },
];

function monter(
  animateurs: Animateur[],
  options: { editingLocked?: boolean; saved?: number; gel?: EtatGel[] } = {},
) {
  const saveMany = vi.fn(async () => options.saved ?? animateurs.length);
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ReferenceDataStore, useValue: { typologies: signal(TYPOLOGIES) } },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ReferenceCrudService, useValue: { saveMany } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { animateurs } },
      { provide: EditionsApi, useValue: { gel: vi.fn(async () => options.gel ?? []) } },
    ],
  });
  return { fixture: TestBed.createComponent(AnimateurBulkEditDialog), saveMany, close };
}

function racine(fixture: ComponentFixture<AnimateurBulkEditDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function bouton(fixture: ComponentFixture<AnimateurBulkEditDialog>): HTMLButtonElement {
  return racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;
}

function submit(fixture: ComponentFixture<AnimateurBulkEditDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

/** Picks an option of a `mat-select` the way a user does: open, then click. */
async function choisir(
  fixture: ComponentFixture<AnimateurBulkEditDialog>,
  name: string,
  libelle: string,
): Promise<void> {
  const select = racine(fixture).querySelector(`mat-select[name="${name}"]`) as HTMLElement;
  (select.querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
  await fixture.whenStable();
  const option = Array.from(document.querySelectorAll('mat-option')).find(
    (each) => each.textContent!.trim() === libelle,
  );
  expect(option, `option « ${libelle} » absente`).toBeDefined();
  (option as HTMLElement).click();
  await fixture.whenStable();
}

function payloads(saveMany: ReturnType<typeof vi.fn>): Animateur[] {
  return (saveMany.mock.calls[0] as unknown as [string, Animateur[]])[1];
}

describe('AnimateurBulkEditDialog', () => {
  it('says how many animateurs the edit is about and changes nothing by default', async () => {
    const { fixture } = monter(ANIMATEURS);
    await fixture.whenStable();

    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe('Modifier 2 animateurs');
    expect(bouton(fixture).disabled).toBe(true);
  });

  it('sets the manager flag on every selected animateur, leaving the rest alone', async () => {
    const { fixture, saveMany, close } = monter(ANIMATEURS);
    await fixture.whenStable();

    await choisir(fixture, 'manager', 'Oui');
    expect(bouton(fixture).disabled).toBe(false);

    submit(fixture);
    await fixture.whenStable();

    expect(payloads(saveMany).map((each) => [each.id, each.manager])).toEqual([
      ['a1', true],
      ['a2', true],
    ]);
    // The three untouched edits must leave every other field exactly as it was.
    expect(payloads(saveMany)[0].competences).toEqual({ ambiance: 'DEBUTANT' });
    expect(payloads(saveMany)[1].joursIndisponibles).toEqual(['2026-07-14']);
    expect(close).toHaveBeenCalledWith(true);
  });

  it('keeps the appreciation fields inert until an action is chosen', async () => {
    const { fixture } = monter(ANIMATEURS);
    await fixture.whenStable();

    const typologie = racine(fixture).querySelector('mat-select[name="competenceTypologie"]')!;
    expect(typologie.getAttribute('aria-disabled')).toBe('true');

    await choisir(fixture, 'competenceMode', 'Ajouter');
    expect(typologie.getAttribute('aria-disabled')).toBe('false');
  });

  it('closes the appreciation under a competences freeze, and leaves the rest of the batch open', async () => {
    const { fixture, saveMany } = monter(ANIMATEURS, {
      gel: [
        {
          famille: 'COMPETENCES',
          libelle: 'Compétences des animateurs',
          fige: true,
          figeLe: '2026-07-01T08:00:00Z',
        },
      ],
    });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('app-gel-notice .gel-notice')).not.toBeNull();
    for (const name of ['competenceMode', 'competenceTypologie', 'competenceNiveau']) {
      expect(
        racine(fixture).querySelector(`mat-select[name="${name}"]`)!.getAttribute('aria-disabled'),
        name,
      ).toBe('true');
    }
    await choisir(fixture, 'manager', 'Oui');
    submit(fixture);
    await fixture.whenStable();
    expect(payloads(saveMany).map((each) => each.manager)).toEqual([true, true]);
  });

  it('waits for a typologie before enabling an appreciation edit', async () => {
    const { fixture, saveMany } = monter(ANIMATEURS);
    await fixture.whenStable();

    await choisir(fixture, 'competenceMode', 'Ajouter');
    // An action with no typologie describes nothing: the button stays dead.
    expect(bouton(fixture).disabled).toBe(true);
    submit(fixture);
    await fixture.whenStable();
    expect(saveMany).not.toHaveBeenCalled();

    await choisir(fixture, 'competenceTypologie', 'Expert');
    expect(bouton(fixture).disabled).toBe(false);
  });

  it('adds an appreciation to everyone, overwriting the level of those who already had it', async () => {
    const { fixture, saveMany } = monter(ANIMATEURS);
    await fixture.whenStable();

    await choisir(fixture, 'competenceMode', 'Ajouter');
    await choisir(fixture, 'competenceTypologie', 'Ambiance');
    await choisir(fixture, 'competenceNiveau', 'Référent');
    submit(fixture);
    await fixture.whenStable();

    expect(payloads(saveMany).map((each) => each.competences)).toEqual([
      { ambiance: 'REFERENT' },
      { ambiance: 'REFERENT' },
    ]);
  });

  it('leaves the level select locked on a removal, which has no level to pick', async () => {
    const { fixture, saveMany } = monter(ANIMATEURS);
    await fixture.whenStable();

    await choisir(fixture, 'competenceMode', 'Retirer');
    await choisir(fixture, 'competenceTypologie', 'Ambiance');
    expect(
      racine(fixture)
        .querySelector('mat-select[name="competenceNiveau"]')!
        .getAttribute('aria-disabled'),
    ).toBe('true');

    submit(fixture);
    await fixture.whenStable();
    expect(payloads(saveMany).map((each) => each.competences)).toEqual([{}, {}]);
  });

  it('clears everyone’s souhaits when replacing them with nothing', async () => {
    const { fixture, saveMany } = monter(ANIMATEURS);
    await fixture.whenStable();

    await choisir(fixture, 'souhaitsMode', 'Remplacer');
    // Deliberate: "remplacer par rien" is the only way to empty the field in
    // bulk, so this is the one mode enabled with no typologie picked.
    expect(bouton(fixture).disabled).toBe(false);

    submit(fixture);
    await fixture.whenStable();
    expect(payloads(saveMany).map((each) => each.souhaits)).toEqual([[], []]);
  });

  it('adds an unavailable day to everyone, without duplicating it for those who had it', async () => {
    const { fixture, saveMany } = monter(ANIMATEURS);
    await fixture.whenStable();

    await choisir(fixture, 'indispoMode', 'Ajouter');
    expect(bouton(fixture).disabled).toBe(true); // no day yet

    const jour = racine(fixture).querySelector('input[name="indispoJour"]') as HTMLInputElement;
    jour.value = '2026-07-14';
    jour.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(bouton(fixture).disabled).toBe(false);

    submit(fixture);
    await fixture.whenStable();
    expect(payloads(saveMany).map((each) => each.joursIndisponibles)).toEqual([
      ['2026-07-14'],
      ['2026-07-14'],
    ]);
  });

  it('keeps the dialog open when the server saved nothing', async () => {
    const { fixture, close } = monter(ANIMATEURS, { saved: 0 });
    await fixture.whenStable();

    await choisir(fixture, 'manager', 'Non');
    submit(fixture);
    await fixture.whenStable();

    expect(close).not.toHaveBeenCalled();
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = monter(ANIMATEURS, { editingLocked: true });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(bouton(fixture).disabled).toBe(true);
  });

  it('cancels without writing anything', async () => {
    const { fixture, saveMany, close } = monter(ANIMATEURS);
    await fixture.whenStable();

    (racine(fixture).querySelector('mat-dialog-actions button') as HTMLButtonElement).click();

    expect(saveMany).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledWith(false);
  });
});
