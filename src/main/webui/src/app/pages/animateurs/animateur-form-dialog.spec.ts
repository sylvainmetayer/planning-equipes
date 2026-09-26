// The richest reference form, and the one holding personal data: birth date
// (from which the whole minor/adult legal regime is re-derived), appreciation
// per typologie, wishes and unavailable days.
//
// Rendered rather than driven through `save()`, like `stand-form-dialog.spec.ts`:
// the repeated appreciation rows name their controls with a `[name]` binding,
// and two rows sharing a name register once instead of twice — a defect that
// only exists once the template runs. The under-16 warning and the chips of
// unavailable days are template-only as well.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { EditionsApi } from '../../core/api/editions-api';
import { Animateur, EtatGel } from '../../core/models';
import { provideRouter } from '@angular/router';
import { AnimateurFormDialog } from './animateur-form-dialog';
import { noDraftStorage, fakeDialogRef, memoryStorage } from '../../core/testing/brouillon';
import {
  LOCAL_DRAFT_STORAGE,
  SESSION_DRAFT_STORAGE,
  DraftStorage,
  draftKey,
  writeDraft,
} from '../../core/brouillon-formulaire';

const TYPOLOGIES = [
  { id: 'ambiance', label: 'Ambiance' },
  { id: 'expert', label: 'Expert' },
];

function animateur(overrides: Partial<Animateur> = {}): Animateur {
  return {
    id: 'a1',
    prenom: 'Amélie',
    nom: 'Nothomb',
    dateNaissance: '1990-05-04',
    manager: false,
    email: null,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...overrides,
  };
}

function monter(
  donnee: Animateur | null,
  options: {
    editingLocked?: boolean;
    saveOk?: boolean;
    storages?: { local: DraftStorage; session: DraftStorage };
    /** What the freeze read answers; nothing frozen by default. */
    gel?: EtatGel[];
    /** The edition's days, as its timeslots give them; none by default. */
    jours?: string[];
  } = {},
) {
  const save = vi.fn(async () => options.saveOk ?? true);
  const close = vi.fn();
  const gel = vi.fn(async () => options.gel ?? []);
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: ReferenceDataStore,
        useValue: {
          typologies: signal(TYPOLOGIES),
          creneaux: signal((options.jours ?? []).map((date) => ({ date }))),
        },
      },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ReferenceCrudService, useValue: { save } },
      { provide: EditionsApi, useValue: { gel } },
      { provide: MatDialogRef, useValue: fakeDialogRef(close) },
      ...(options.storages
        ? [
            { provide: LOCAL_DRAFT_STORAGE, useValue: options.storages.local },
            { provide: SESSION_DRAFT_STORAGE, useValue: options.storages.session },
          ]
        : noDraftStorage()),
      { provide: MAT_DIALOG_DATA, useValue: { animateur: donnee } },
    ],
  });
  return { fixture: TestBed.createComponent(AnimateurFormDialog), save, close, gel };
}

function racine(fixture: ComponentFixture<AnimateurFormDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function champ(fixture: ComponentFixture<AnimateurFormDialog>, name: string): HTMLInputElement {
  return racine(fixture).querySelector(`input[name="${name}"]`) as HTMLInputElement;
}

function saisir(
  fixture: ComponentFixture<AnimateurFormDialog>,
  name: string,
  valeur: string,
): void {
  const input = champ(fixture, name);
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

/** Clicks the button whose visible label is exactly `libelle` (icon ligature aside). */
function cliquer(fixture: ComponentFixture<AnimateurFormDialog>, libelle: string): void {
  const bouton = Array.from(racine(fixture).querySelectorAll('button')).find(
    (each) => each.textContent?.replace(/^(add|delete|save|cancel)/, '').trim() === libelle,
  );
  expect(bouton, `bouton « ${libelle} » absent`).toBeDefined();
  bouton!.click();
}

function submit(fixture: ComponentFixture<AnimateurFormDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

function payload(save: ReturnType<typeof vi.fn>): Animateur {
  return (save.mock.calls[0] as unknown as [string, Animateur])[1];
}

/** Names the `<form>` really registered — a `[name]` binding leaves no attribute. */
function nomsEnregistres(fixture: ComponentFixture<AnimateurFormDialog>): string[] {
  const ngForm = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
  return Object.keys(ngForm.controls);
}

describe('AnimateurFormDialog', () => {
  let erreursConsole: unknown[][];

  beforeEach(() => {
    erreursConsole = [];
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) =>
      erreursConsole.push(args),
    );
  });

  it('names every control, so the labels render and no NG01352 is thrown', async () => {
    const { fixture } = monter(animateur());
    await fixture.whenStable();

    expect(nomsEnregistres(fixture)).toContain('id');
    expect(nomsEnregistres(fixture)).toContain('manager');
    expect(erreursConsole.filter((args) => JSON.stringify(args).includes('NG01352'))).toEqual([]);
  });

  it('fills the identity fields from the animateur it was opened on', async () => {
    const { fixture } = monter(animateur({ id: 'a42', email: 'amelie@exemple.test' }));
    await fixture.whenStable();

    expect(champ(fixture, 'id').value).toBe('a42');
    expect(champ(fixture, 'prenom').value).toBe('Amélie');
    expect(champ(fixture, 'nom').value).toBe('Nothomb');
    expect(champ(fixture, 'dateNaissance').value).toBe('1990-05-04');
    expect(champ(fixture, 'email').value).toBe('amelie@exemple.test');
    expect(champ(fixture, 'id').readOnly).toBe(true);
  });

  // The id is drawn per edition and names nobody; a dialog title is never
  // logged, so it may carry the identity.
  it('titles itself after the animateur it was opened on, not the id', async () => {
    const { fixture } = monter(animateur({ id: 'a42' }));
    await fixture.whenStable();

    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe(
      "Modifier l'animateur Amélie Nothomb",
    );
  });

  // The snack bar may name the person; the log it is copied to must not.
  it('hands the name to the save as personal data', async () => {
    const { fixture, save } = monter(animateur({ id: 'a42' }));
    await fixture.whenStable();

    submit(fixture);
    await fixture.whenStable();

    expect(save).toHaveBeenCalledWith('animateurs', expect.anything(), 'a42', 'Animateur', {
      text: 'Amélie Nothomb',
      personal: true,
    });
  });

  it('starts blank on a creation, asking no identifier: the server draws it', async () => {
    const { fixture } = monter(null);
    await fixture.whenStable();

    expect(champ(fixture, 'id')).toBeNull();
    expect(champ(fixture, 'prenom').value).toBe('');
    expect(racine(fixture).querySelector('h2')!.textContent!.trim()).toBe('Nouvel animateur');
  });

  it('warns about the reinforced rules for an animateur under 16, and only then', async () => {
    const { fixture } = monter(animateur({ dateNaissance: '1990-05-04' }));
    await fixture.whenStable();
    expect(racine(fixture).querySelector('.form-warning')).toBeNull();

    const recent = new Date();
    recent.setFullYear(recent.getFullYear() - 14);
    saisir(fixture, 'dateNaissance', recent.toISOString().slice(0, 10));
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.form-warning')!.textContent!).toContain(
      'moins de 16 ans',
    );
  });

  it('does not warn on an unparseable or empty birth date', async () => {
    const { fixture } = monter(animateur({ dateNaissance: '' }));
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.form-warning')).toBeNull();
  });

  it('shows the appreciations in words, read-only, and leads to the grid on that one row', async () => {
    const { fixture, save } = monter(
      animateur({ competences: { ambiance: 'REFERENT', expert: 'DEBUTANT' } }),
    );
    await fixture.whenStable();

    expect(
      Array.from(
        racine(fixture).querySelectorAll('.animateur-appreciations .animateur-pastille'),
      ).map((each) => each.textContent!.trim()),
    ).toEqual(['Ambiance · Référent', 'Expert · Débutant']);
    // Never the enum's own name.
    expect(racine(fixture).textContent).not.toContain('DEBUTANT');
    const lien = Array.from(racine(fixture).querySelectorAll('a')).find((each) =>
      each.textContent!.includes('Saisir dans la grille'),
    )!;
    expect(lien.getAttribute('href')).toBe('/competences?animateur=a1');

    // Typed in the grid, not here: the save keeps them as they are.
    submit(fixture);
    await fixture.whenStable();
    expect(payload(save).competences).toEqual({ ambiance: 'REFERENT', expert: 'DEBUTANT' });
  });

  it('says so when nothing is declared, instead of showing an empty block', async () => {
    const { fixture } = monter(animateur());
    await fixture.whenStable();

    const hints = Array.from(racine(fixture).querySelectorAll('.empty-hint')).map((each) =>
      each.textContent!.trim(),
    );
    expect(hints).toContain('Aucune appréciation déclarée.');
    expect(hints).toContain('Disponible tous les jours.');
  });

  it('ticks the days off on the frieze of the edition, and says « absent du 8 au 12 » in one range', async () => {
    const { fixture, save } = monter(animateur(), {
      jours: ['2026-07-08', '2026-07-09', '2026-07-10', '2026-07-12', '2026-07-20'],
    });
    await fixture.whenStable();

    const jours = () =>
      Array.from(racine(fixture).querySelectorAll<HTMLButtonElement>('.indispo-jour'));
    expect(jours()).toHaveLength(5);
    jours()[4].click();
    await fixture.whenStable();
    expect(jours()[4].getAttribute('aria-pressed')).toBe('true');

    saisir(fixture, 'rangeStart', '2026-07-08');
    saisir(fixture, 'rangeEnd', '2026-07-12');
    await fixture.whenStable();
    cliquer(fixture, 'event_busyMarquer absent');
    await fixture.whenStable();
    expect(racine(fixture).textContent).toContain("4 jour(s) de l'édition marqué(s) absent(s)");

    submit(fixture);
    await fixture.whenStable();
    expect(payload(save).joursIndisponibles).toEqual([
      '2026-07-08',
      '2026-07-09',
      '2026-07-10',
      '2026-07-12',
      '2026-07-20',
    ]);
  });

  it('names each removal button after the day it removes', async () => {
    const { fixture } = monter(animateur({ joursIndisponibles: ['2026-07-14', '2026-07-15'] }));
    await fixture.whenStable();

    expect(
      Array.from(racine(fixture).querySelectorAll('mat-chip button')).map((each) =>
        each.getAttribute('aria-label'),
      ),
    ).toEqual(['Retirer 2026-07-14', 'Retirer 2026-07-15']);
  });

  it('saves the trimmed identity and an absent e-mail or phone as null, never as an empty string', async () => {
    const { fixture, save, close } = monter(null);
    await fixture.whenStable();

    saisir(fixture, 'prenom', '  Marcel  ');
    saisir(fixture, 'nom', '  Proust  ');
    saisir(fixture, 'dateNaissance', '1871-07-10');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    expect(payload(save)).toEqual({
      // Empty on a creation: the shared CRUD drops it, the server draws one.
      id: '',
      prenom: 'Marcel',
      nom: 'Proust',
      dateNaissance: '1871-07-10',
      manager: false,
      email: null,
      telephone: null,
      competences: {},
      souhaits: [],
      joursIndisponibles: [],
      // The precondition of issue #362: null on a fixture that never carried a stamp.
      modifieLe: null,
    });
    expect(close).toHaveBeenCalledWith(true);
  });

  // The server refuses a fiche without these three (issue #432), naming every
  // missing field at once; the form does not let the click happen until they
  // are there, and a blank name counts as missing.
  it('holds the submit until prénom, nom and date de naissance are filled', async () => {
    const { fixture, save } = monter(null);
    await fixture.whenStable();
    const bouton = () =>
      racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement;

    for (const name of ['prenom', 'nom', 'dateNaissance']) {
      expect(champ(fixture, name).required, `${name} required`).toBe(true);
    }
    expect(bouton().disabled).toBe(true);

    saisir(fixture, 'prenom', '   ');
    saisir(fixture, 'nom', 'Proust');
    saisir(fixture, 'dateNaissance', '1871-07-10');
    await fixture.whenStable();
    expect(bouton().disabled).toBe(true);
    // Ctrl+Enter reaches `save()` through the form itself: it must hold too.
    submit(fixture);
    await fixture.whenStable();
    expect(save).not.toHaveBeenCalled();

    saisir(fixture, 'prenom', 'Marcel');
    await fixture.whenStable();
    expect(bouton().disabled).toBe(false);
  });

  it('keeps the dialog open when the save is refused, and closes on cancel', async () => {
    const { fixture, close } = monter(animateur(), { saveOk: false });
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();
    expect(close).not.toHaveBeenCalled();

    (racine(fixture).querySelector('mat-dialog-actions button') as HTMLButtonElement).click();
    expect(close).toHaveBeenCalledWith(false);
  });

  it('disables the whole form and says why while a solve is running', async () => {
    const { fixture } = monter(animateur(), { editingLocked: true });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(
      (racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  // The padlock's notice sits inside the frozen branch, so it cannot be what
  // reads the freeze: on a fresh reload the form itself has to.
  describe('draft', () => {
    // The acceptance criterion of the whole feature for this form: an
    // identity, a birth date and an e-mail never land in localStorage.
    it('writes the draft to sessionStorage, never to localStorage', async () => {
      const storages = { local: memoryStorage(), session: memoryStorage() };
      const { fixture } = monter(animateur(), { storages });
      await fixture.whenStable();

      saisir(fixture, 'nom', 'Nothomb-Martin');
      await fixture.whenStable();
      // Destroyed with the write pending, as a navigation would: it goes out now.
      fixture.destroy();

      expect(storages.local.length).toBe(0);
      expect(storages.session.length).toBe(1);
      const written = JSON.parse(storages.session.getItem(storages.session.key(0)!)!);
      expect(written.draft.nom).toBe('Nothomb-Martin');
    });

    it('offers the interrupted entry back, and takes it on « Reprendre »', async () => {
      const session = memoryStorage();
      writeDraft(
        session,
        draftKey('animateur', 'a1'),
        {
          ...animateur(),
          nom: 'Interrompue',
          email: '',
          telephone: '',
          competences: [],
          modifieLe: null,
        },
        null,
      );
      const { fixture, save } = monter(animateur(), {
        storages: { local: memoryStorage(), session },
      });
      await fixture.whenStable();

      expect(racine(fixture).querySelector('app-draft-banner')).not.toBeNull();
      expect(champ(fixture, 'nom').value).toBe('Nothomb');

      cliquer(fixture, 'Reprendre');
      await fixture.whenStable();
      expect(racine(fixture).querySelector('app-draft-banner')).toBeNull();

      submit(fixture);
      await fixture.whenStable();
      expect(payload(save).nom).toBe('Interrompue');
      expect(session.length).toBe(0);
    });

    it('offers nothing to another fiche', async () => {
      const session = memoryStorage();
      writeDraft(session, draftKey('animateur', 'a2'), { id: 'a2' }, null);
      const { fixture } = monter(animateur(), {
        storages: { local: memoryStorage(), session },
      });
      await fixture.whenStable();

      expect(racine(fixture).querySelector('app-draft-banner')).toBeNull();
    });

    it('closes an untouched fiche without a question', async () => {
      const { fixture, close } = monter(animateur());
      await fixture.whenStable();

      cliquer(fixture, 'Annuler');
      await fixture.whenStable();

      expect(close).toHaveBeenCalledWith(false);
    });
  });
});
