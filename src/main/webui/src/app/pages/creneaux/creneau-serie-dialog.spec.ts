// The série dialog, rendered: the preview is what protects a grid from a rule
// that is one hour off, so what is asserted is the wiring around it — the
// payload sent, nothing written before « Créer », a verdict with an error
// keeping the button off, and an edited rule sending the user back to the
// preview.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { RapportRecurrence } from '../../core/models';
import { CreneauSerieDialog } from './creneau-serie-dialog';

function apercu(patch: Partial<RapportRecurrence['controle']> = {}): RapportRecurrence {
  return {
    nombreGeneres: 2,
    creneaux: [
      { id: 0, jour: 0, date: '2026-07-06', heureDebut: '09:00', heureFin: '12:00' },
      { id: 0, jour: 0, date: '2026-07-06', heureDebut: '14:00', heureFin: '18:00' },
    ],
    controle: {
      nombreCreneaux: 2,
      anomalies: [],
      ouvertures: [],
      faisabilite: null,
      ...patch,
    },
  };
}

function monter(
  options: {
    editingLocked?: boolean;
    reponse?: RapportRecurrence;
    controleActuel?: RapportRecurrence['controle'];
  } = {},
) {
  // Two stubs, not one: a test must tell a preview from a write.
  const preview = vi.fn(async () => options.reponse ?? apercu());
  const post = vi.fn(async () => options.reponse ?? apercu());
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: CreneauxApi, useValue: { previewRecurrence: preview, createRecurrence: post } },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: MatDialogRef, useValue: { close } },
      {
        provide: MAT_DIALOG_DATA,
        useValue: { controleActuel: options.controleActuel ?? null },
      },
    ],
  });
  return { fixture: TestBed.createComponent(CreneauSerieDialog), preview, post, close };
}

function racine(fixture: ComponentFixture<CreneauSerieDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function saisir(fixture: ComponentFixture<CreneauSerieDialog>, name: string, valeur: string): void {
  const input = racine(fixture).querySelector<HTMLInputElement>(`input[name="${name}"]`)!;
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

function bouton(fixture: ComponentFixture<CreneauSerieDialog>, libelle: string): HTMLButtonElement {
  return Array.from(racine(fixture).querySelectorAll('button')).find((each) =>
    each.textContent!.includes(libelle),
  )!;
}

async function remplirRegle(fixture: ComponentFixture<CreneauSerieDialog>): Promise<void> {
  saisir(fixture, 'fenetres', '09:00-12:00, 14:00-18:00');
  saisir(fixture, 'dateDebut', '2026-07-06');
  saisir(fixture, 'dateFin', '2026-07-07');
  await fixture.whenStable();
}

describe('CreneauSerieDialog', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('says what stops the rule from being sent, and keeps both buttons off until it is sound', async () => {
    const { fixture } = monter();
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.field-error')!.textContent).toContain('fenêtres');
    expect(bouton(fixture, 'Prévisualiser').disabled).toBe(true);
    expect(bouton(fixture, 'Créer la série').disabled).toBe(true);

    saisir(fixture, 'fenetres', '09:00-');
    await fixture.whenStable();
    expect(racine(fixture).querySelector('.field-error')!.textContent).toContain("n'a pas de fin");

    await remplirRegle(fixture);
    expect(racine(fixture).querySelector('.field-error')).toBeNull();
    expect(bouton(fixture, 'Prévisualiser').disabled).toBe(false);
    // No preview yet: nothing to create from.
    expect(bouton(fixture, 'Créer la série').disabled).toBe(true);
  });

  it('previews on the server with the structured rule, writing nothing, then allows the creation', async () => {
    const { fixture, preview, post, close } = monter();
    await fixture.whenStable();
    await remplirRegle(fixture);

    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    expect(preview).toHaveBeenCalledOnce();
    const [corps] = preview.mock.calls[0] as unknown as [unknown];
    expect(corps).toEqual({
      jours: 'TOUS',
      dateDebut: '2026-07-06',
      dateFin: '2026-07-07',
      joursSemaine: [],
      dates: [],
      exclusions: [],
      fenetres: [
        { heureDebut: '09:00', heureFin: '12:00' },
        { heureDebut: '14:00', heureFin: '18:00' },
      ],
    });
    expect(racine(fixture).querySelector('.serie-apercu h3')!.textContent).toContain(
      '2 créneau(x)',
    );
    expect(racine(fixture).querySelectorAll('.vacation-chip')).toHaveLength(2);
    expect(bouton(fixture, 'Créer la série').disabled).toBe(false);

    bouton(fixture, 'Créer la série').click();
    await fixture.whenStable();
    expect(post).toHaveBeenCalledExactlyOnceWith(corps);
    expect(close).toHaveBeenCalledWith(apercu());
  });

  it('keeps the creation off when the resulting grid would carry an error, and says why', async () => {
    const { fixture, close } = monter({
      reponse: apercu({
        anomalies: [
          {
            severite: 'ERREUR',
            type: 'DOUBLON',
            date: '2026-07-06',
            message: 'Doublon 09:00-12:00',
          },
        ],
      }),
    });
    await fixture.whenStable();
    await remplirRegle(fixture);

    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    expect(racine(fixture).textContent).toContain('ajouterait 1 erreur(s)');
    expect(racine(fixture).querySelector('.serie-anomalies')!.textContent).toContain('Doublon');
    expect(bouton(fixture, 'Créer la série').disabled).toBe(true);
    expect(close).not.toHaveBeenCalled();
  });

  // Le verdict couvre toute la grille : une erreur déjà là ne doit pas
  // interdire d'écrire une règle qui, elle, est correcte.
  it('does not block on an error the grid already carried', async () => {
    const deja = {
      severite: 'ERREUR' as const,
      type: 'REPOS_QUOTIDIEN_IMPOSSIBLE' as const,
      date: '2026-07-06',
      message: 'Vacation trop longue',
    };
    const { fixture } = monter({
      reponse: apercu({ anomalies: [deja] }),
      controleActuel: {
        nombreCreneaux: 2,
        anomalies: [deja],
        ouvertures: [],
        faisabilite: null,
      },
    });
    await fixture.whenStable();
    await remplirRegle(fixture);
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    expect(bouton(fixture, 'Créer la série').disabled).toBe(false);
  });

  it('sends the user back to the preview once the rule changed', async () => {
    const { fixture } = monter();
    await fixture.whenStable();
    await remplirRegle(fixture);
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();
    expect(bouton(fixture, 'Créer la série').disabled).toBe(false);

    saisir(fixture, 'exclusions', '2026-07-07');
    await fixture.whenStable();

    expect(racine(fixture).textContent).toContain('prévisualisez à nouveau');
    expect(bouton(fixture, 'Créer la série').disabled).toBe(true);
  });

  it('reveals the weekday boxes on the weekday scope, and the dates field on the date scope', async () => {
    const { fixture } = monter();
    await fixture.whenStable();
    const composant = fixture.componentInstance as unknown as { patch(patch: object): void };

    composant.patch({ jours: 'JOURS_SEMAINE' });
    await fixture.whenStable();
    expect(racine(fixture).querySelectorAll('.horaire-jours-semaine mat-checkbox')).toHaveLength(7);

    composant.patch({ jours: 'DATES' });
    await fixture.whenStable();
    expect(racine(fixture).querySelector('input[name="dates"]')).not.toBeNull();
    expect(racine(fixture).querySelector('input[name="dateDebut"]')).toBeNull();
  });

  it('locks everything while a solve runs', async () => {
    const { fixture } = monter({ editingLocked: true });
    await fixture.whenStable();
    expect((racine(fixture).querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    expect(bouton(fixture, 'Prévisualiser').disabled).toBe(true);
  });
});
