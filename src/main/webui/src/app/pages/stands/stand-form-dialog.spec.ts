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
import { Creneau, Emplacement, HoraireStand, Stand } from '../../core/models';
import { StandFormDialog } from './stand-form-dialog';
import { noDraftStorage, fakeDialogRef } from '../../core/testing/brouillon';

const CRENEAUX: Creneau[] = [
  { id: 1, jour: 1, date: '2026-07-14', heureDebut: '10:00', heureFin: '12:00' },
  { id: 2, jour: 1, date: '2026-07-14', heureDebut: '14:00', heureFin: '19:00' },
  { id: 3, jour: 2, date: '2026-07-15', heureDebut: '10:00', heureFin: '12:00' },
];

const EMPLACEMENTS: Emplacement[] = [
  { id: 'hall', nom: 'Hall A', latitude: null, longitude: null },
];

const TYPOLOGIES = [
  { id: 'ambiance', label: 'Ambiance' },
  { id: 'expert', label: 'Expert' },
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
    ...overrides,
  };
}

let erreursConsole: unknown[][] = [];

function regle(overrides: Partial<HoraireStand> = {}): HoraireStand {
  return {
    id: null,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: [],
    motif: null,
    ...overrides,
  };
}

/** The compact line of the first rule. */
function ligne(fixture: ComponentFixture<StandFormDialog>): HTMLInputElement {
  return root(fixture).querySelector<HTMLInputElement>('input[name="fenetresLigne0"]')!;
}

function taper(champ: HTMLInputElement, valeur: string): void {
  champ.value = valeur;
  champ.dispatchEvent(new Event('input'));
}

function mount(donnee: Stand | null, options: { editingLocked?: boolean; stands?: Stand[] } = {}) {
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
          stands: signal(options.stands ?? []),
        },
      },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ReferenceCrudService, useValue: { save } },
      { provide: MatDialogRef, useValue: fakeDialogRef(close) },
      ...noDraftStorage(),
      { provide: MAT_DIALOG_DATA, useValue: { stand: donnee } },
    ],
  });
  return { fixture: TestBed.createComponent(StandFormDialog), save, close };
}

function root(fixture: ComponentFixture<StandFormDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** Every control of the form that `ngModel` binds — the population NG01352 applies to. */
function controles(fixture: ComponentFixture<StandFormDialog>): Element[] {
  return Array.from(
    root(fixture).querySelectorAll('form input[matInput], form mat-select, form mat-checkbox'),
  );
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
    each.textContent?.includes(libelle),
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

    const champs = Array.from(
      root(fixture).querySelectorAll('form input[matInput], form mat-select'),
    );
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
    expect((root(existant).querySelector('input[name="id"]') as HTMLInputElement).readOnly).toBe(
      true,
    );

    TestBed.resetTestingModule();
    const { fixture: nouveau } = mount(null);
    await nouveau.whenStable();
    expect((root(nouveau).querySelector('input[name="id"]') as HTMLInputElement).readOnly).toBe(
      false,
    );
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
    expect(submit(fixture).disabled).toBe(true);
    // NOTE: the template also writes `[attr.aria-invalid]="effectifInvalid() || null"`
    // on these two inputs, and it never reaches the DOM: `matInput` host-binds
    // `aria-invalid` from its own `errorState`, which stays false because no
    // Angular validator expresses "max >= min". Asserted as it really is, so
    // this test does not claim an accessibility guarantee the app does not
    // give; reported separately rather than fixed here.
    expect(min.getAttribute('aria-invalid')).toBe('false');
  });

  // Issue #343: a stand always carries a typologie, and the form says so before the server does.
  it('shows the typologies error and blocks the submit without any typologie', async () => {
    const { fixture } = mount(stand({ typologiesProposees: [] }));
    await fixture.whenStable();

    const erreur = root(fixture).querySelector('#stand-typologies-erreur');
    expect(erreur).not.toBeNull();
    expect(erreur!.getAttribute('role')).toBe('alert');
    expect(erreur!.textContent).toContain('au moins une typologie');
    expect(submit(fixture).disabled).toBe(true);
  });

  it('accepts a stand whose bounds are coherent', async () => {
    const { fixture } = mount(stand({ effectifMin: 1, effectifMax: 3 }));
    await fixture.whenStable();

    expect(root(fixture).querySelector('#stand-effectif-erreur')).toBeNull();
    expect(submit(fixture).disabled).toBe(false);
  });

  function submit(fixture: ComponentFixture<StandFormDialog>): HTMLButtonElement {
    return root(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;
  }

  it('previews one cell per event day, deduplicated across créneaux', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    // Three créneaux over two days: the strip shows the days, not the créneaux.
    const cellules = root(fixture).querySelectorAll('.apercu-jour');
    expect(cellules).toHaveLength(2);
    expect(
      Array.from(cellules).map((cell) => cell.querySelector('.apercu-date')!.textContent!.trim()),
    ).toEqual(['14/07', '15/07']);
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
            motif: null,
          },
        ],
      }),
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
    expect(noms).toContain('fenetresLigne0');
    expect(noms).toContain('fenetresLigne1');
  });

  it('opens a plain rule folded: one line of windows, no mode or day selector', async () => {
    const { fixture } = mount(
      stand({ horaires: [regle({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] })] }),
    );
    await fixture.whenStable();

    expect(nomsEnregistres(fixture)).not.toContain('horaireMode0');
    expect(nomsEnregistres(fixture)).not.toContain('horaireJours0');
    expect(root(fixture).querySelector('.horaire-resume')!.textContent!.trim()).toBe(
      'Ouvert tous les jours',
    );
    expect(ligne(fixture).value).toBe('10:00-12:00');
  });

  it('unfolds the selectors behind « Cas particulier », keeping what the rule already said', async () => {
    const { fixture } = mount(
      stand({ horaires: [regle({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] })] }),
    );
    await fixture.whenStable();

    const bouton = root(fixture).querySelector<HTMLButtonElement>('.horaire-cas-particulier')!;
    expect(bouton.getAttribute('aria-expanded')).toBe('false');
    bouton.click();
    await fixture.whenStable();

    expect(bouton.getAttribute('aria-expanded')).toBe('true');
    expect(nomsEnregistres(fixture)).toContain('horaireMode0');
    expect(nomsEnregistres(fixture)).toContain('horaireJours0');
    expect(nomsEnregistres(fixture)).toContain('horaireMotif0');
    expect(ligne(fixture).value).toBe('10:00-12:00');
  });

  it('opens a closing rule unfolded, with nothing to fold it back on', async () => {
    const { fixture } = mount(
      stand({
        horaires: [
          regle({ mode: 'FERMETURE', fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] }),
        ],
      }),
    );
    await fixture.whenStable();

    expect(nomsEnregistres(fixture)).toContain('horaireMode0');
    expect(root(fixture).querySelector('.horaire-cas-particulier')).toBeNull();
    expect(root(fixture).querySelector('.horaire-resume')).toBeNull();
  });

  it('starts a new rule on an empty line and says what to type there', async () => {
    const { fixture } = mount(stand());
    await fixture.whenStable();

    cliquer(fixture, "Ajouter une règle d'horaire");
    await fixture.whenStable();

    expect(ligne(fixture).value).toBe('');
    const erreur = root(fixture).querySelector('#stand-horaire-erreur-0')!;
    expect(erreur.textContent).toContain('au moins une fenêtre');
    // Material prepends its own hint id: the error id must be among them.
    expect(ligne(fixture).getAttribute('aria-describedby')!.split(/\s+/)).toContain(
      'stand-horaire-erreur-0',
    );
    expect(submit(fixture).disabled).toBe(true);
  });

  it('keeps a line that does not parse as typed, reports it on its card and blocks the submit', async () => {
    const { fixture } = mount(
      stand({
        horaires: [
          regle({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] }),
          regle({ mode: 'FERMETURE', fenetres: [{ heureDebut: '13:00', heureFin: '14:00' }] }),
        ],
      }),
    );
    await fixture.whenStable();

    taper(ligne(fixture), '10:00-12:00, 14:0');
    await fixture.whenStable();

    expect(ligne(fixture).value).toBe('10:00-12:00, 14:0');
    const cartes = root(fixture).querySelectorAll('.horaire-carte');
    expect(cartes[0].classList.contains('horaire-carte-erreur')).toBe(true);
    expect(cartes[0].querySelector('.field-error')!.textContent).toContain('14:0');
    // The other rule is fine and says nothing: one error, on the card it is about.
    expect(cartes[1].querySelector('.field-error')).toBeNull();
    expect(submit(fixture).disabled).toBe(true);
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
            motif: null,
          },
        ],
      }),
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
      'Dimanche',
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
            motif: null,
          },
        ],
      }),
    );
    await fixture.whenStable();

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    expect(alertes.some((text) => text.includes('au moins une fenêtre'))).toBe(true);
    expect(submit(fixture).disabled).toBe(true);
  });

  it('sends the effectif typed on the line, and null for a window left without one', async () => {
    const { fixture, save } = mount(
      stand({
        id: 's7',
        effectifMax: 4,
        horaires: [
          regle({
            fenetres: [
              { heureDebut: '10:00', heureFin: '12:00' },
              { heureDebut: '14:00', heureFin: null, effectif: 2 },
            ],
          }),
        ],
      }),
    );
    await fixture.whenStable();

    expect(ligne(fixture).value).toBe('10:00-12:00, 14:00-@2');
    taper(ligne(fixture), '10:00-12:00@3, 14:00-');
    await fixture.whenStable();

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const [, payload] = save.mock.calls[0] as unknown as [string, Stand];
    expect(payload.horaires[0].fenetres).toEqual([
      { heureDebut: '10:00', heureFin: '12:00', effectif: 3 },
      { heureDebut: '14:00', heureFin: null, effectif: null },
    ]);
    // The form's own state never reaches the backend.
    expect(payload.horaires[0]).not.toHaveProperty('saisie');
  });

  it('details the windows row by row on demand, both views editing the same windows', async () => {
    const { fixture, save } = mount(
      stand({
        id: 's7',
        effectifMax: 4,
        horaires: [
          regle({
            fenetres: [
              { heureDebut: '10:00', heureFin: '12:00' },
              { heureDebut: '14:00', heureFin: null, effectif: 2 },
            ],
          }),
        ],
      }),
    );
    await fixture.whenStable();

    cliquer(fixture, 'Détailler fenêtre par fenêtre');
    await fixture.whenStable();

    const effectifs = Array.from(
      root(fixture).querySelectorAll<HTMLInputElement>('.horaire-fenetre-row input[type="number"]'),
    );
    expect(effectifs).toHaveLength(2);
    expect(effectifs[0].value).toBe('');
    expect(effectifs[1].value).toBe('2');
    taper(effectifs[0], '3');
    await fixture.whenStable();

    cliquer(fixture, 'Revenir à la ligne');
    await fixture.whenStable();
    expect(ligne(fixture).value).toBe('10:00-12:00@3, 14:00-@2');

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    const [, payload] = save.mock.calls[0] as unknown as [string, Stand];
    expect(payload.horaires[0].fenetres.map((fenetre) => fenetre.effectif)).toEqual([3, 2]);
  });

  it('shows the stand minimum as the placeholder of an effectif left empty, and follows it as typed', async () => {
    const { fixture } = mount(
      stand({
        id: 's7b',
        effectifMin: 2,
        effectifMax: 4,
        horaires: [regle({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] })],
        ouvertures: [
          {
            id: null,
            date: '2026-07-14',
            heureDebut: '14:00',
            heureFin: null,
            motif: null,
            effectif: null,
          },
        ],
      }),
    );
    await fixture.whenStable();

    cliquer(fixture, 'Détailler fenêtre par fenêtre');
    await fixture.whenStable();
    const champs = () =>
      Array.from(
        root(fixture).querySelectorAll<HTMLInputElement>('.fenetre-effectif input[type="number"]'),
      );
    // One per window, one per dated opening: both read the same minimum.
    expect(champs()).toHaveLength(2);
    expect(champs().map((champ) => champ.placeholder)).toEqual(['2', '2']);

    taper(root(fixture).querySelector<HTMLInputElement>('input[name="effectifMin"]')!, '3');
    await fixture.whenStable();
    expect(champs().map((champ) => champ.placeholder)).toEqual(['3', '3']);
  });

  it('refuses a zero effectif on a window, says why and blocks the submit', async () => {
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
            fenetres: [{ heureDebut: '10:00', heureFin: '12:00', effectif: 0 }],
            motif: null,
          },
        ],
      }),
    );
    await fixture.whenStable();

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    expect(alertes.some((text) => text.includes("L'effectif d'une fenêtre"))).toBe(true);
    expect(submit(fixture).disabled).toBe(true);
  });

  it('refuses a zero effectif on a dated opening, and accepts one from one up', async () => {
    const { fixture, save } = mount(
      stand({
        id: 's8',
        effectifMax: 6,
        ouvertures: [
          {
            id: null,
            date: '2026-07-14',
            heureDebut: '14:00',
            heureFin: null,
            motif: null,
            effectif: 0,
          },
        ],
      }),
    );
    await fixture.whenStable();

    let alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    expect(alertes.some((text) => text.includes("L'effectif d'une ouverture"))).toBe(true);
    expect(submit(fixture).disabled).toBe(true);

    const champ = root(fixture).querySelector<HTMLInputElement>(
      '.indisponibilite-row input[type="number"]',
    )!;
    champ.value = '5';
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    expect(alertes.some((text) => text.includes("L'effectif d'une ouverture"))).toBe(false);
    expect(submit(fixture).disabled).toBe(false);
    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    const [, payload] = save.mock.calls[0] as unknown as [string, Stand];
    expect(payload.ouvertures[0].effectif).toBe(5);
  });

  it('refuses a window asking for more than the stand can hold, and says so', async () => {
    const { fixture } = mount(
      stand({
        effectifMin: 1,
        effectifMax: 2,
        horaires: [
          {
            id: null,
            mode: 'OUVERTURE',
            jours: 'TOUS',
            joursSemaine: [],
            dateDebut: null,
            dateFin: null,
            dates: [],
            fenetres: [{ heureDebut: '10:00', heureFin: '12:00', effectif: 5 }],
            motif: null,
          },
        ],
      }),
    );
    await fixture.whenStable();

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    // The two numbers that disagree, not a sentence about zero.
    expect(alertes.some((text) => text.includes('(5)') && text.includes('(2)'))).toBe(true);
    expect(submit(fixture).disabled).toBe(true);
  });

  it('refuses a day carrying both a closure and an opening', async () => {
    const { fixture } = mount(
      stand({
        indisponibilites: [
          { id: null, date: '2026-07-14', heureDebut: '10:00', heureFin: null, motif: null },
        ],
        ouvertures: [
          { id: null, date: '2026-07-14', heureDebut: '14:00', heureFin: null, motif: null },
        ],
      }),
    );
    await fixture.whenStable();

    const alertes = Array.from(root(fixture).querySelectorAll('.field-error')).map(
      (each) => each.textContent!,
    );
    expect(alertes.some((text) => text.includes('à la fois une fermeture et une ouverture'))).toBe(
      true,
    );
    expect(submit(fixture).disabled).toBe(true);
  });

  /** The stand whose typical day the tests below copy: one rule with a named effectif, one dated opening. */
  function pavillon(): Stand {
    return stand({
      id: 'PAVILLON',
      nom: 'Pavillon',
      effectifMax: 4,
      horaires: [
        regle({ id: 7, fenetres: [{ heureDebut: '14:00', heureFin: null, effectif: 3 }] }),
      ],
      ouvertures: [
        {
          id: 11,
          date: '2026-07-15',
          heureDebut: '10:00',
          heureFin: '12:00',
          motif: null,
          effectif: null,
        },
      ],
    });
  }

  function copyFrom(fixture: ComponentFixture<StandFormDialog>, standId: string): void {
    (
      fixture.componentInstance as unknown as { copyHorairesFrom(id: string | null): void }
    ).copyHorairesFrom(standId);
  }

  function apercu(fixture: ComponentFixture<StandFormDialog>): string[] {
    return Array.from(root(fixture).querySelectorAll('.apercu-jour .apercu-detail')).map((cell) =>
      cell.textContent!.trim(),
    );
  }

  it('offers to copy the schedule of every other stand, never of the one being edited', async () => {
    const { fixture } = mount(stand({ id: 's1' }), { stands: [stand({ id: 's1' }), pavillon()] });
    await fixture.whenStable();

    const select = root(fixture).querySelector('mat-select[name="copyHorairesFrom"]')!;
    expect(select).not.toBeNull();
    expect(nomAccessible(root(fixture), select)).toBe('Copier les horaires de…');
    const modeles = (
      fixture.componentInstance as unknown as { standsModeles(): Stand[] }
    ).standsModeles();
    expect(modeles.map((each) => each.id)).toEqual(['PAVILLON']);
  });

  it('hides the copy field when there is no other stand to copy from', async () => {
    const { fixture } = mount(stand({ id: 's1' }), { stands: [stand({ id: 's1' })] });
    await fixture.whenStable();

    expect(root(fixture).querySelector('mat-select[name="copyHorairesFrom"]')).toBeNull();
  });

  it('replaces the draft schedule with the chosen stand’s and refreshes the preview, without saving', async () => {
    const { fixture, save } = mount(
      stand({
        id: 's1',
        effectifMax: 4,
        horaires: [regle({ id: 3, fenetres: [{ heureDebut: '09:00', heureFin: '11:00' }] })],
      }),
      { stands: [pavillon()] },
    );
    await fixture.whenStable();
    expect(apercu(fixture)).toEqual(['Ouvert 09:00 → 11:00', 'Ouvert 09:00 → 11:00']);

    copyFrom(fixture, 'PAVILLON');
    await fixture.whenStable();

    // The rule replaced the stand's own; the dated opening wins on its day.
    expect(apercu(fixture)).toEqual(['Ouvert 14:00 → fermeture ×3', 'Ouvert 10:00 → 12:00']);
    expect(ligne(fixture).value).toBe('14:00-@3');
    expect(root(fixture).querySelector('.stand-copie-statut')!.textContent).toContain(
      'Horaires de « Pavillon » copiés : 1 règle(s), 1 exception(s) datée(s)',
    );
    expect(save).not.toHaveBeenCalled();
    expect(submit(fixture).disabled).toBe(false);

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    const [, payload] = save.mock.calls[0] as unknown as [string, Stand];
    // New rows of this stand: no id travels from the model.
    expect(payload.horaires[0].id).toBeNull();
    expect(payload.horaires[0].fenetres).toEqual([
      { heureDebut: '14:00', heureFin: null, effectif: 3 },
    ]);
    expect(payload.ouvertures[0].id).toBeNull();
  });

  it('warns when a copied window asks for more than this stand holds, until the maximum is raised', async () => {
    const { fixture } = mount(stand({ id: 's1', effectifMin: 1, effectifMax: 2 }), {
      stands: [pavillon()],
    });
    await fixture.whenStable();

    copyFrom(fixture, 'PAVILLON');
    await fixture.whenStable();

    const avertissement = root(fixture).querySelector('.stand-copie-avertissement')!;
    expect(avertissement).not.toBeNull();
    expect(avertissement.textContent).toContain('1 fenêtre(s)');
    expect(avertissement.textContent).toContain('(2)');
    // The rule editor's own check blocks the submit, as the server would refuse it.
    expect(submit(fixture).disabled).toBe(true);

    const max = root(fixture).querySelector<HTMLInputElement>('input[name="effectifMax"]')!;
    taper(max, '3');
    await fixture.whenStable();

    expect(root(fixture).querySelector('.stand-copie-avertissement')).toBeNull();
    expect(submit(fixture).disabled).toBe(false);
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = mount(stand(), { editingLocked: true });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect(
      (root(fixture).querySelector('fieldset.form-fieldset') as HTMLFieldSetElement).disabled,
    ).toBe(true);
    expect(submit(fixture).disabled).toBe(true);
  });

  it('saves the edited stand and closes on success', async () => {
    const { fixture, save, close } = mount(stand({ id: 's42', nom: 'Dixit' }));
    await fixture.whenStable();

    root(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(save).toHaveBeenCalledOnce();
    const [resource, payload, editingId] = save.mock.calls[0] as unknown as [
      string,
      Stand,
      string | null,
    ];
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
            stands: signal([]),
          },
        },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: ReferenceCrudService, useValue: { save } },
        { provide: MatDialogRef, useValue: fakeDialogRef(close) },
        ...noDraftStorage(),
        { provide: MAT_DIALOG_DATA, useValue: { stand: stand() } },
      ],
    });
    const fixture = TestBed.createComponent(StandFormDialog);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement)
      .querySelector('form')!
      .dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(save).toHaveBeenCalledOnce();
    expect(close).not.toHaveBeenCalled();
  });
});
