// The derivation dialog, rendered: the payload the preview sends, nothing
// written before « Écrire », a replacement asked for first, and the range
// prefilled from the grid the page already has.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { RapportDerivation, Stand } from '../../core/models';
import { CreneauDerivationDialog } from './creneau-derivation-dialog';

function apercu(patch: Partial<RapportDerivation> = {}): RapportDerivation {
  return {
    nombreGeneres: 2,
    creneaux: [
      { id: 0, jour: 0, date: '2026-07-06', heureDebut: '10:00', heureFin: '12:00' },
      { id: 0, jour: 0, date: '2026-07-06', heureDebut: '14:00', heureFin: '20:00' },
    ],
    coupures: [
      { date: '2026-07-06', heure: '10:00:00', standIds: ['A', 'B'], nombreStands: 5 },
      { date: '2026-07-06', heure: '12:00:00', standIds: ['A'], nombreStands: 1 },
    ],
    joursSansFenetre: ['2026-07-07'],
    controle: {
      nombreCreneaux: 2,
      anomalies: [],
      ouvertures: [],
      faisabilite: null,
    },
    ...patch,
  };
}

function monter(
  options: {
    reponse?: RapportDerivation;
    confirme?: boolean;
    dates?: [string | null, string | null];
  } = {},
) {
  // Two stubs, not one: a test must tell a preview from a write.
  const preview = vi.fn(async () => options.reponse ?? apercu());
  const post = vi.fn(async () => options.reponse ?? apercu());
  const close = vi.fn();
  const ask = vi.fn(async () => options.confirme ?? true);
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: CreneauxApi, useValue: { previewDerivation: preview, derive: post } },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      {
        provide: ReferenceDataStore,
        useValue: { stands: signal([{ id: 'A', nom: 'Bourse aux jeux' } as Stand]) },
      },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: ConfirmService, useValue: { ask } },
      { provide: MatDialogRef, useValue: { close } },
      {
        provide: MAT_DIALOG_DATA,
        useValue: {
          dateDebut: options.dates ? options.dates[0] : '2026-07-06',
          dateFin: options.dates ? options.dates[1] : '2026-07-07',
        },
      },
    ],
  });
  return { fixture: TestBed.createComponent(CreneauDerivationDialog), preview, post, close, ask };
}

function racine(fixture: ComponentFixture<CreneauDerivationDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function bouton(
  fixture: ComponentFixture<CreneauDerivationDialog>,
  libelle: string,
): HTMLButtonElement {
  return Array.from(racine(fixture).querySelectorAll('button')).find((each) =>
    each.textContent!.includes(libelle),
  )!;
}

describe('CreneauDerivationDialog', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('is prefilled with the grid dates and a 20:00 closing, and previews with the structured request', async () => {
    const { fixture, preview } = monter();
    await fixture.whenStable();

    expect(racine(fixture).querySelector<HTMLInputElement>('input[name="dateDebut"]')!.value).toBe(
      '2026-07-06',
    );
    expect(bouton(fixture, 'Ajouter à la grille').disabled).toBe(true);

    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    expect(preview).toHaveBeenCalledWith({
      dateDebut: '2026-07-06',
      dateFin: '2026-07-07',
      heureFermeture: '20:00',
      dureeMinimaleMinutes: 15,
      remplacer: false,
    });
    expect(racine(fixture).querySelectorAll('.vacation-chip')).toHaveLength(2);
    expect(racine(fixture).textContent).toContain('2 coupure(s)');
    expect(racine(fixture).textContent).toContain('2026-07-07');
    expect(bouton(fixture, 'Ajouter à la grille').disabled).toBe(false);
  });

  it('names the stands at a cut by their name, an unknown id kept as-is', async () => {
    const { fixture } = monter();
    await fixture.whenStable();
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    const titre = racine(fixture).querySelector('.derivation-coupures')!.getAttribute('title');
    expect(titre).toBe('10:00 (Bourse aux jeux, B et 3 autre(s)) · 12:00 (Bourse aux jeux)');
  });

  it('asks nothing when adding, writes, and closes with the report', async () => {
    const { fixture, post, close, ask } = monter();
    await fixture.whenStable();
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    bouton(fixture, 'Ajouter à la grille').click();
    await fixture.whenStable();

    expect(ask).not.toHaveBeenCalled();
    expect(post).toHaveBeenCalledExactlyOnceWith(expect.anything());
    expect(close).toHaveBeenCalledWith(apercu());
  });

  it('replaces the grid only after the preview, on an explicit confirmation', async () => {
    const { fixture, post, ask } = monter({ confirme: false });
    await fixture.whenStable();
    // Nothing to replace with before a preview.
    expect(bouton(fixture, 'Remplacer la grille').disabled).toBe(true);
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    bouton(fixture, 'Remplacer la grille').click();
    await fixture.whenStable();

    expect(ask).toHaveBeenCalledOnce();
    expect(post).not.toHaveBeenCalled();
  });

  it('replaces the grid once confirmed, sending remplacer', async () => {
    const { fixture, post } = monter();
    await fixture.whenStable();
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    bouton(fixture, 'Remplacer la grille').click();
    await fixture.whenStable();

    expect(post).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ remplacer: true }));
  });

  it('keeps the write off on an empty derivation or a blocking verdict, and says why', async () => {
    const vide = monter({
      reponse: apercu({
        nombreGeneres: 0,
        creneaux: [],
        coupures: [],
        joursSansFenetre: ['2026-07-06', '2026-07-07'],
      }),
    });
    await vide.fixture.whenStable();
    bouton(vide.fixture, 'Prévisualiser').click();
    await vide.fixture.whenStable();
    expect(racine(vide.fixture).textContent).toContain('rien à dériver');
    expect(bouton(vide.fixture, 'Ajouter à la grille').disabled).toBe(true);

    const bloquee = monter({
      reponse: apercu({
        controle: {
          nombreCreneaux: 4,
          anomalies: [
            { severite: 'ERREUR', type: 'DOUBLON', date: '2026-07-06', message: 'Doublon' },
          ],
          ouvertures: [],
          faisabilite: null,
        },
      }),
    });
    await bloquee.fixture.whenStable();
    bouton(bloquee.fixture, 'Prévisualiser').click();
    await bloquee.fixture.whenStable();
    expect(racine(bloquee.fixture).textContent).toContain('remplacez la grille');
    expect(bouton(bloquee.fixture, 'Ajouter à la grille').disabled).toBe(true);
    // Replacing stays possible: the doublon is the existing grid's.
    expect(bouton(bloquee.fixture, 'Remplacer la grille').disabled).toBe(false);
  });

  it('refuses an empty range or a missing closing time before calling the server', async () => {
    const { fixture, preview } = monter({ dates: [null, null] });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.field-error')!.textContent).toContain(
      'première et la dernière date',
    );
    expect(bouton(fixture, 'Prévisualiser').disabled).toBe(true);
    expect(preview).not.toHaveBeenCalled();
  });
});
