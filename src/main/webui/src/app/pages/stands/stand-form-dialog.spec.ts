// A rendering test, against the project's taste for logic tests, because the
// defect class it guards lives in the template and nowhere else: an `ngModel`
// inside a `<form>` with no `name` throws NG01352 at init, Angular Material
// then leaves the `mat-label` unrendered, and the field loses its accessible
// name — the exact defect `verrouillages-page.spec.ts` was written for, on the
// richest form of the application.
//
// This one repeats its controls inside `@for` blocks, so the names must also be
// unique per row: two controls sharing a name inside one form silently
// overwrite each other's registration.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau, Emplacement, Stand } from '../../core/models';
import { StandFormDialog } from './stand-form-dialog';

const CRENEAUX: Creneau[] = [
  { id: 1, jour: 1, date: '2026-07-14', heureDebut: '10:00', heureFin: '12:00' },
  { id: 2, jour: 1, date: '2026-07-14', heureDebut: '14:00', heureFin: '19:00' },
  { id: 3, jour: 2, date: '2026-07-15', heureDebut: '10:00', heureFin: '12:00' }
];

const EMPLACEMENTS: Emplacement[] = [
  { id: 'hall', nom: 'Hall A', latitude: null, longitude: null }
];

const TYPOLOGIES = [
  { id: 'ambiance', label: 'Ambiance' },
  { id: 'expert', label: 'Expert' }
];

function stand(overrides: Partial<Stand> = {}): Stand {
  return {
    id: 's1',
    nom: 'Loup-Garou',
    typologiesProposees: ['ambiance'],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...overrides
  };
}

let erreursConsole: unknown[][] = [];

function mount(donnee: Stand | null, options: { editingLocked?: boolean } = {}) {
  const save = vi.fn(async () => true);
  const close = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: ReferenceDataStore,
        useValue: {
          typologies: signal(TYPOLOGIES),
          creneaux: signal(CRENEAUX),
          emplacements: signal(EMPLACEMENTS),
          stands: signal([])
        }
      },
      { provide: SolverJobService, useValue: { editingLocked: signal(options.editingLocked ?? false) } },
      { provide: ReferenceCrudService, useValue: { save } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { stand: donnee } }
    ]
  });
  return { fixture: TestBed.createComponent(StandFormDialog), save, close };
}

function root(fixture: ComponentFixture<StandFormDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** Every control of the form that `ngModel` binds — the population NG01352 applies to. */
function controles(fixture: ComponentFixture<StandFormDialog>): Element[] {
  return Array.from(root(fixture).querySelectorAll('form input[matInput], form mat-select, form mat-checkbox'));
}

/**
 * The names the `<form>` actually registered its controls under.
 *
 * Read from `NgForm` rather than from a `name` attribute: only a *static*
 * `name="x"` leaves an attribute in the DOM, and this template names its
 * repeated rows with a `[name]` binding, which does not. The registration is
 * also the thing that matters — an unnamed `ngModel` throws NG01352 and
 * registers nothing, and two rows sharing a name register once instead of
 * twice. Both show up here as a missing key, and nowhere else.
 */
function nomsEnregistres(fixture: ComponentFixture<StandFormDialog>): string[] {
  const ngForm = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
  return Object.keys(ngForm.controls);
}

/**
 * The name a screen reader — and `getByLabel` — would announce for a control,
 * resolved through `aria-labelledby` the way Angular Material wires a
 * `mat-label` to the control of its `mat-form-field`.
 */
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

/** Clicks the button whose visible label contains `libelle`. */
function cliquer(fixture: ComponentFixture<StandFormDialog>, libelle: string): void {
  const bouton = Array.from(root(fixture).querySelectorAll('button')).find((each) =>
    each.textContent?.includes(libelle)
  );
  expect(bouton, `bouton « ${libelle} » absent`).toBeDefined();
  bouton!.click();
}

describe('StandFormDialog', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    erreursConsole = [];
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
      erreursConsole.push(args);
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('binds every control to a named form control, which is what makes the label render', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    // One registered control per rendered control: an unnamed `ngModel` throws
    // NG01352 and registers nothing, so the count is what catches it.
    expect(nomsEnregistres(fixture)).toHaveLength(controles(fixture).length);
  });

  it('throws no NG01352 while building the form', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    const ng01352 = erreursConsole.filter((args) => JSON.stringify(args).includes('NG01352'));
    expect(ng01352).toEqual([]);
  });

  it('gives every field of the identity section an accessible name', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    const champs = Array.from(root(fixture).querySelectorAll('form input[matInput], form mat-select'));
    for (const champ of champs) {
      expect(nomAccessible(root(fixture), champ), champ.outerHTML.slice(0, 120)).not.toBe('');
    }
  });

  it('fills the form from the stand it was opened on', async () => {
    const { fixture } = mount(stand({ id: 's42', nom: 'Dixit', effectifMin: 2, effectifMax: 4 }));
    await fixture.whenStable();

    const valeur = (name: string) =>
      (root(fixture).querySelector(`input[name="${name}"]`) as HTMLInputElement).value;
    expect(valeur('id')).toBe('s42');
    expect(valeur('nom')).toBe('Dixit');
    expect(valeur('effectifMin')).toBe('2');
    expect(valeur('effectifMax')).toBe('4');
  });

  it('locks the identifier of an existing stand but not of a new one', async () => {
    const { fixture: existant } = mount(stand());
    await existant.whenStable();
    expect((root(existant).querySelector('input[name="id"]') as HTMLInputElement).readOnly).toBe(true);

    TestBed.resetTestingModule();
    const { fixture: nouveau } = mount(null);
    await nouveau.whenStable();
    expect((root(nouveau).querySelector('input[name="id"]') as HTMLInputElement).readOnly).toBe(false);
  });

  it('shows the staffing error and blocks the submit when the maximum is below the minimum', async () => {
    const { fixture } = mount(stand({ effectifMin: 5, effectifMax: 2 }));
    await fixture.whenStable();

    const erreur = root(fixture).querySelector('#stand-effectif-erreur');
    expect(erreur).not.toBeNull();
    expect(erreur!.getAttribute('role')).toBe('alert');
    // The message is tied to the two fields it is about, not just floating nearby.
    const min = root(fixture).querySelector('input[name="effectifMin"]')!;
    expect(min.getAttribute('aria-describedby')).toBe('stand-effectif-erreur');
    expect(soumettre(fixture).disabled).toBe(true);
    // NOTE: the template also writes `[attr.aria-invalid]="effectifInvalid() || null"`
    // on these two inputs, and it never reaches the DOM: `matInput` host-binds
    // `aria-invalid` from its own `errorState`, which stays false because no
    // Angular validator expresses "max >= min". Asserted as it really is, so
    // this test does not claim an accessibility guarantee the app does not
    // give; reported separately rather than fixed here.
    expect(min.getAttribute('aria-invalid')).toBe('false');
  });

  it('accepts a stand whose bounds are coherent', async () => {
    const { fixture } = mount(stand({ effectifMin: 1, effectifMax: 3 }));
    await fixture.whenStable();

    expect(root(fixture).querySelector('#stand-effectif-erreur')).toBeNull();
    expect(soumettre(fixture).disabled).toBe(false);
  });

  function soumettre(fixture: ComponentFixture<StandFormDialog>): HTMLButtonElement {
    return root(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;
  }

  it('previews one cell per festival day, deduplicated across créneaux', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    // Three créneaux over two days: the strip shows the days, not the créneaux.
    const cellules = root(fixture).querySelectorAll('.apercu-jour');
    expect(cellules).toHaveLength(2);
    expect(Array.from(cellules).map((cell) => cell.querySelector('.apercu-date')!.textContent!.trim())).toEqual([
      '14/07',
      '15/07'
    ]);
  });

  it('describes a day narrowed by a recurring rule in the preview', async () => {
    const { fixture } = mount(
      stand({
        horaires: [
          {
            id: null,
            mode: 'OUVERTURE',
            jours: 'TOUS',
            joursSemaine: [],
            dateDebut: null,
            dateFin: null,
            dates: [],
            fenetres: [{ heureDebut: '14:00', heureFin: null }],
            motif: null
          }
        ]
      })
    );
    await fixture.whenStable();

    const premier = root(fixture).querySelector('.apercu-jour .apercu-detail')!.textContent!.trim();
    expect(premier).toBe('Ouvert 14:00 → fermeture');
  });

  it('adds a recurring rule and names its controls uniquely per row', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    cliquer(fixture, "Ajouter une règle d'horaire");
    await fixture.whenStable();
    cliquer(fixture, "Ajouter une règle d'horaire");
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.horaire-carte')).toHaveLength(2);
    // Two rows of identically-shaped controls: a shared name would make the
    // second row overwrite the first one's registration, leaving fewer
    // registered controls than rendered ones.
    const noms = nomsEnregistres(fixture);
    expect(noms).toHaveLength(controles(fixture).length);
    expect(new Set(noms).size).toBe(noms.length);
    expect(noms).toContain('horaireMode0');
    expect(noms).toContain('horaireMode1');
  });

  it('reveals the seven weekday checkboxes, correctly labelled, on the weekday scope', async () => {
    const { fixture } = mount(
      stand({
        horaires: [
          {
            id: null,
            mode: 'OUVERTURE',
            jours: 'JOURS_SEMAINE',
            joursSemaine: ['SATURDAY'],
            dateDebut: null,
            dateFin: null,
            dates: [],
            fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }],
            motif: null
          }
        ]
      })
    );
    await fixture.whenStable();

    const cases = Array.from(root(fixture).querySelectorAll('.horaire-jours-semaine mat-checkbox'));
    expect(cases.map((each) => each.textContent!.trim())).toEqual([
      'Lundi',
      'Mardi',
      'Mercredi',
      'Jeudi',
      'Vendredi',
      'Samedi',
      'Dimanche'
    ]);
  });

  it('shows the recurring-rule error and blocks the submit for a rule with no window', async () => {
    const { fixture } = mount(
      stand({
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
            motif: null
          }
        ]
      })
    );
    await fixture.whenStable();

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map((each) => each.textContent!);
    expect(alertes.some((texte) => texte.includes('au moins une fenêtre'))).toBe(true);
    expect(soumettre(fixture).disabled).toBe(true);
  });

  it('refuses a day carrying both a closure and an opening', async () => {
    const { fixture } = mount(
      stand({
        indisponibilites: [{ id: null, date: '2026-07-14', heureDebut: '10:00', heureFin: null, motif: null }],
        ouvertures: [{ id: null, date: '2026-07-14', heureDebut: '14:00', heureFin: null, motif: null }]
      })
    );
    await fixture.whenStable();

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map((each) => each.textContent!);
    expect(alertes.some((texte) => texte.includes('à la fois une fermeture et une ouverture'))).toBe(true);
    expect(soumettre(fixture).disabled).toBe(true);
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = mount(stand(), { editingLocked: true });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((root(fixture).querySelector('fieldset.form-fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(soumettre(fixture).disabled).toBe(true);
  });

  it('saves the edited stand and closes on success', async () => {
    const { fixture, save, close } = mount(stand({ id: 's42', nom: 'Dixit' }));
    await fixture.whenStable();

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(save).toHaveBeenCalledOnce();
    const [resource, payload, editingId] = save.mock.calls[0] as unknown as [string, Stand, string | null];
    expect(resource).toBe('stands');
    expect(payload.id).toBe('s42');
    expect(payload.nom).toBe('Dixit');
    expect(editingId).toBe('s42');
    expect(close).toHaveBeenCalledWith(true);
  });

  it('does not save an invalid stand, even if the form is submitted', async () => {
    const { fixture, save, close } = mount(stand({ effectifMin: 5, effectifMax: 2 }));
    await fixture.whenStable();

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(save).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();
  });

  it('keeps the dialog open when the save is refused', async () => {
    TestBed.resetTestingModule();
    const close = vi.fn();
    const save = vi.fn(async () => false);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ReferenceDataStore,
          useValue: {
            typologies: signal(TYPOLOGIES),
            creneaux: signal(CRENEAUX),
            emplacements: signal(EMPLACEMENTS),
            stands: signal([])
          }
        },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: ReferenceCrudService, useValue: { save } },
        { provide: MatDialogRef, useValue: { close } },
        { provide: MAT_DIALOG_DATA, useValue: { stand: stand() } }
      ]
    });
    const fixture = TestBed.createComponent(StandFormDialog);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(save).toHaveBeenCalledOnce();
    expect(close).not.toHaveBeenCalled();
  });
});
