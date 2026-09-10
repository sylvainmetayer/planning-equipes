// A rendering test, for the same reason as `stand-form-dialog.spec.ts`: the
// template is the whole editor and it was at 0 % — an `ngModel` inside a
// `<form>` with no `name` throws NG01352 and silently costs every field its
// label.
//
// The rule this dialog exists to enforce is "ne pas modifier by default": the
// submit stays disabled until the user fills something in, and only what was
// filled in is written. That rule is expressed by the disabled state of one
// button, which no logic test observes.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Emplacement, Stand } from '../../core/models';
import { StandBulkEditDialog } from './stand-bulk-edit-dialog';
import { StandBulkPatch } from './stand-bulk-edit';

const EMPLACEMENTS: Emplacement[] = [
  { id: 'hall', nom: 'Hall A', latitude: null, longitude: null },
];

const TYPOLOGIES = [
  { id: 'ambiance', label: 'Ambiance' },
  { id: 'expert', label: 'Expert' },
];

function stand(id: string, overrides: Partial<Stand> = {}): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...overrides,
  };
}

let erreursConsole: unknown[][] = [];

/**
 * `stands` is the *selection* the dialog was opened on. The store deliberately
 * holds a wider list: a bulk edit must write the selected rows, never every
 * stand of the edition, and the two can only be told apart if they differ.
 */
function mount(stands: Stand[], options: { editingLocked?: boolean; saveMany?: number } = {}) {
  const saveMany = vi.fn(async () => options.saveMany ?? stands.length);
  const tousLesStands = [...stands, stand('hors-selection')];
  const close = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: ReferenceDataStore,
        useValue: {
          typologies: signal(TYPOLOGIES),
          creneaux: signal([]),
          emplacements: signal(EMPLACEMENTS),
          stands: signal(tousLesStands),
        },
      },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ReferenceCrudService, useValue: { saveMany } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { stands } },
    ],
  });
  return { fixture: TestBed.createComponent(StandBulkEditDialog), saveMany, close };
}

function root(fixture: ComponentFixture<StandBulkEditDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function controles(fixture: ComponentFixture<StandBulkEditDialog>): Element[] {
  return Array.from(
    root(fixture).querySelectorAll('form input[matInput], form mat-select, form mat-checkbox'),
  );
}

/** See `stand-form-dialog.spec.ts`: the registration, not the `name` attribute, is what tells the truth. */
function nomsEnregistres(fixture: ComponentFixture<StandBulkEditDialog>): string[] {
  const ngForm = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
  return Object.keys(ngForm.controls);
}

function nomAccessible(racine: HTMLElement, controle: Element): string {
  const ids = controle.getAttribute('aria-labelledby')?.split(/\s+/) ?? [];
  const parLabelledBy = ids
    .map((id) => racine.querySelector(`[id="${id}"]`)?.textContent?.trim() ?? '')
    .join(' ')
    .trim();
  if (parLabelledBy !== '') {
    return parLabelledBy;
  }
  const id = controle.getAttribute('id');
  return id ? (racine.querySelector(`label[for="${id}"]`)?.textContent?.trim() ?? '') : '';
}

/** The "Ajouter une règle d'horaire" button, or `undefined` while it is not offered. */
function boutonAjouterHoraire(
  fixture: ComponentFixture<StandBulkEditDialog>,
): HTMLButtonElement | undefined {
  return Array.from(root(fixture).querySelectorAll('button')).find((each) =>
    each.textContent?.includes("Ajouter une règle d'horaire"),
  );
}

function submit(fixture: ComponentFixture<StandBulkEditDialog>): HTMLButtonElement {
  return root(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;
}

/** Writes into the dialog's patch signal the way a filled-in field would. */
async function fill(
  fixture: ComponentFixture<StandBulkEditDialog>,
  patch: Partial<StandBulkPatch>,
): Promise<void> {
  (fixture.componentInstance as unknown as { update(patch: Partial<StandBulkPatch>): void }).update(
    patch,
  );
  await fixture.whenStable();
}

describe('StandBulkEditDialog', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    erreursConsole = [];
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
      erreursConsole.push(args);
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('binds every control to a named form control, which is what makes the label render', async () => {
    const { fixture } = mount([stand('s1'), stand('s2')]);
    await fixture.whenStable();

    expect(nomsEnregistres(fixture)).toHaveLength(controles(fixture).length);
  });

  it('throws no NG01352 while building the form', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();

    expect(erreursConsole.filter((args) => JSON.stringify(args).includes('NG01352'))).toEqual([]);
  });

  it('gives every field an accessible name', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();

    for (const champ of Array.from(
      root(fixture).querySelectorAll('form input[matInput], form mat-select'),
    )) {
      expect(nomAccessible(root(fixture), champ), champ.outerHTML.slice(0, 120)).not.toBe('');
    }
  });

  it('names each field of the opened form exactly, in order', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();

    // Pinned one by one rather than merely "non-empty": a dropped `mat-label`
    // leaves Material naming the control from something else, so only the
    // expected wording catches it.
    const champs = Array.from(
      root(fixture).querySelectorAll('form input[matInput], form mat-select'),
    );
    expect(champs.map((champ) => nomAccessible(root(fixture), champ))).toEqual([
      'Action',
      'Emplacement',
      'Action',
      'Typologies de jeu',
      'Effectif minimum',
      'Effectif maximum',
      'Réservé aux majeurs',
      'Premium (stand éditeur)',
      "Niveau d'effort",
      'Que faire des horaires',
    ]);
  });

  it('says how many stands the edit covers', async () => {
    const { fixture } = mount([stand('s1'), stand('s2'), stand('s3')]);
    await fixture.whenStable();

    expect(root(fixture).querySelector('h2')!.textContent).toContain('3');
  });

  it('opens with every field on "ne pas modifier" and the submit disabled', async () => {
    const { fixture } = mount([stand('s1'), stand('s2')]);
    await fixture.whenStable();

    // The safety property of the whole screen: opening it and pressing save
    // must be incapable of changing anything.
    expect(submit(fixture).disabled).toBe(true);
    expect(root(fixture).textContent).toContain(
      'Seuls les champs renseignés ci-dessous sont modifiés',
    );
  });

  it('enables the submit as soon as one field is actually filled in', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();

    await fill(fixture, { effectifMin: 2 });

    expect(submit(fixture).disabled).toBe(false);
  });

  it('keeps the submit disabled for a mode that names no target yet', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();

    // "Définir l'emplacement" without saying which one changes nothing.
    await fill(fixture, { emplacement: { mode: 'DEFINIR', emplacementId: null } });
    expect(submit(fixture).disabled).toBe(true);

    await fill(fixture, { emplacement: { mode: 'DEFINIR', emplacementId: 'hall' } });
    expect(submit(fixture).disabled).toBe(false);
  });

  it('names the stands a staffing patch would leave inconsistent, and blocks the batch', async () => {
    const { fixture } = mount([
      stand('s1', { nom: 'Loup-Garou', effectifMin: 4 }),
      stand('s2', { nom: 'Dixit' }),
    ]);
    await fixture.whenStable();

    // effectifMax 2 against s1's own min of 4: the batch is refused as a whole,
    // and the message has to say which row is the problem.
    await fill(fixture, { effectifMax: 2 });

    const erreur = root(fixture).querySelector('.field-error[role="alert"]');
    expect(erreur).not.toBeNull();
    expect(erreur!.textContent).toContain('Loup-Garou');
    expect(erreur!.textContent).not.toContain('Dixit');
    expect(submit(fixture).disabled).toBe(true);
  });

  it('shows no horaire editor, not even its add button, until a horaire mode is chosen', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.horaire-carte')).toHaveLength(0);
    // The add button is behind the same guard: offering it under "ne pas
    // modifier" would invite rules that the chosen mode then discards.
    expect(boutonAjouterHoraire(fixture)).toBeUndefined();

    await fill(fixture, { horaires: { mode: 'AJOUTER', horaires: [] } });
    expect(boutonAjouterHoraire(fixture)).toBeDefined();
  });

  it('adds horaire rules under the AJOUTER mode, uniquely named per row', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();
    await fill(fixture, { horaires: { mode: 'AJOUTER', horaires: [] } });

    const add = boutonAjouterHoraire(fixture)!;
    add.click();
    await fixture.whenStable();
    add.click();
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.horaire-carte')).toHaveLength(2);
    const noms = nomsEnregistres(fixture);
    expect(noms).toHaveLength(controles(fixture).length);
    expect(new Set(noms).size).toBe(noms.length);
  });

  it('applies a rule typed on the compact line to every selected stand', async () => {
    const { fixture, saveMany } = mount([stand('s1'), stand('s2')]);
    await fixture.whenStable();
    await fill(fixture, { horaires: { mode: 'REMPLACER', horaires: [] } });
    boutonAjouterHoraire(fixture)!.click();
    await fixture.whenStable();

    const ligne = root(fixture).querySelector<HTMLInputElement>(
      'input[name="bulkfenetresLigne0"]',
    )!;
    ligne.value = '14:00-';
    ligne.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const [, payloads] = saveMany.mock.calls[0] as unknown as [string, Stand[]];
    expect(payloads).toHaveLength(2);
    for (const stand of payloads) {
      expect(stand.horaires).toHaveLength(1);
      expect(stand.horaires[0].fenetres).toEqual([
        { heureDebut: '14:00', heureFin: null, effectif: null },
      ]);
      // The editor's own state stays in the form.
      expect(stand.horaires[0]).not.toHaveProperty('saisie');
    }
  });

  it('blocks the batch on an invalid horaire rule and says why', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();
    await fill(fixture, {
      horaires: {
        mode: 'REMPLACER',
        horaires: [
          {
            id: null,
            mode: 'OUVERTURE',
            jours: 'TOUS',
            joursSemaine: [],
            dateDebut: null,
            dateFin: null,
            dates: [],
            fenetres: [],
            motif: null,
          },
        ],
      },
    });

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    expect(alertes.some((text) => text.includes('au moins une fenêtre'))).toBe(true);
    expect(submit(fixture).disabled).toBe(true);
  });

  it('allows EFFACER, which carries no rule to validate', async () => {
    const { fixture } = mount([stand('s1')]);
    await fixture.whenStable();
    await fill(fixture, { horaires: { mode: 'EFFACER', horaires: [] } });

    expect(root(fixture).querySelectorAll('.field-error')).toHaveLength(0);
    expect(submit(fixture).disabled).toBe(false);
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = mount([stand('s1')], { editingLocked: true });
    await fixture.whenStable();
    await fill(fixture, { effectifMin: 2 });

    expect(root(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect(
      (root(fixture).querySelector('fieldset.form-fieldset') as HTMLFieldSetElement).disabled,
    ).toBe(true);
    expect(submit(fixture).disabled).toBe(true);
  });

  it('applies the patch to every selected stand and closes', async () => {
    const { fixture, saveMany, close } = mount([stand('s1'), stand('s2')]);
    await fixture.whenStable();
    await fill(fixture, { premium: 'OUI' });

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(saveMany).toHaveBeenCalledOnce();
    const [resource, payloads] = saveMany.mock.calls[0] as unknown as [string, Stand[]];
    expect(resource).toBe('stands');
    // The store also holds 'hors-selection'; it must not be written.
    expect(payloads.map((each) => each.id)).toEqual(['s1', 's2']);
    // Only the field that was filled in moved; the rest keeps each row's value.
    expect(payloads.every((each) => each.premium)).toBe(true);
    expect(payloads.map((each) => each.effectifMin)).toEqual([1, 1]);
    expect(close).toHaveBeenCalledWith(true);
  });

  it('writes nothing when the form was never filled in, even if submitted', async () => {
    const { fixture, saveMany, close } = mount([stand('s1')]);
    await fixture.whenStable();

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(saveMany).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();
  });

  it('stays open when the batch saved nothing', async () => {
    const { fixture, saveMany, close } = mount([stand('s1')], { saveMany: 0 });
    await fixture.whenStable();
    await fill(fixture, { premium: 'OUI' });

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(saveMany).toHaveBeenCalledOnce();
    expect(close).not.toHaveBeenCalled();
  });
});
