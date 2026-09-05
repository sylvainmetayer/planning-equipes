// The derivation dialog, rendered: the payload the preview sends, nothing
// written before « Écrire », a replacement asked for first, and the range
// prefilled from the grid the page already has.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { RapportDerivation } from '../../core/models';
import { CreneauDerivationDialog } from './creneau-derivation-dialog';

function apercu(patch: Partial<RapportDerivation> = {}): RapportDerivation {
  return {
    nombreGeneres: 2,
    creneaux: [
      { id: 0, jour: 0, date: '2026-07-06', heureDebut: '10:00', heureFin: '12:00' },
      { id: 0, jour: 0, date: '2026-07-06', heureDebut: '14:00', heureFin: '20:00' }
    ],
    coupures: [
      { date: '2026-07-06', heure: '10:00:00', standIds: ['A', 'B'], nombreStands: 5 },
      { date: '2026-07-06', heure: '12:00:00', standIds: ['A'], nombreStands: 1 }
    ],
    joursSansFenetre: ['2026-07-07'],
    controle: { mode: 'AMPLITUDES', nombreCreneaux: 2, anomalies: [], ouvertures: [], faisabilite: null },
    ...patch
  };
}

function monter(options: { reponse?: RapportDerivation; confirme?: boolean; dates?: [string | null, string | null] } = {}) {
  const post = vi.fn(async () => options.reponse ?? apercu());
  const close = vi.fn();
  const ask = vi.fn(async () => options.confirme ?? true);
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { post } },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: ConfirmService, useValue: { ask } },
      { provide: MatDialogRef, useValue: { close } },
      {
        provide: MAT_DIALOG_DATA,
        useValue: { mode: 'AMPLITUDES', dateDebut: options.dates ? options.dates[0] : '2026-07-06', dateFin: options.dates ? options.dates[1] : '2026-07-07' }
      }
    ]
  });
  return { fixture: TestBed.createComponent(CreneauDerivationDialog), post, close, ask };
}

function racine(fixture: ComponentFixture<CreneauDerivationDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function bouton(fixture: ComponentFixture<CreneauDerivationDialog>, libelle: string): HTMLButtonElement {
  return Array.from(racine(fixture).querySelectorAll('button')).find((each) => each.textContent!.includes(libelle))!;
}

describe('CreneauDerivationDialog', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('is prefilled with the grid dates and a 20:00 closing, and previews with the structured request', async () => {
    const { fixture, post } = monter();
    await fixture.whenStable();

    expect(racine(fixture).querySelector<HTMLInputElement>('input[name="dateDebut"]')!.value).toBe('2026-07-06');
    expect(bouton(fixture, 'Écrire la grille').disabled).toBe(true);

    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    expect(post).toHaveBeenCalledWith('/api/creneaux/derivation/apercu?mode=AMPLITUDES', {
      dateDebut: '2026-07-06',
      dateFin: '2026-07-07',
      heureFermeture: '20:00',
      dureeMinimaleMinutes: 15,
      remplacer: false
    });
    expect(racine(fixture).querySelectorAll('.vacation-chip')).toHaveLength(2);
    expect(racine(fixture).textContent).toContain('2 coupure(s)');
    expect(racine(fixture).textContent).toContain('2026-07-07');
    expect(bouton(fixture, 'Écrire la grille').disabled).toBe(false);
  });

  it('asks nothing when adding, writes, and closes with the report', async () => {
    const { fixture, post, close, ask } = monter();
    await fixture.whenStable();
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    bouton(fixture, 'Écrire la grille').click();
    await fixture.whenStable();

    expect(ask).not.toHaveBeenCalled();
    expect((post.mock.calls[1] as unknown as [string])[0]).toBe('/api/creneaux/derivation?mode=AMPLITUDES');
    expect(close).toHaveBeenCalledWith(apercu());
  });

  it('asks before replacing the grid, and writes nothing when refused', async () => {
    const { fixture, post, ask } = monter({ confirme: false });
    await fixture.whenStable();
    (fixture.componentInstance as unknown as { patch(p: object): void }).patch({ remplacer: true });
    await fixture.whenStable();
    bouton(fixture, 'Prévisualiser').click();
    await fixture.whenStable();

    bouton(fixture, 'Écrire la grille').click();
    await fixture.whenStable();

    expect(ask).toHaveBeenCalledOnce();
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('keeps the write off on an empty derivation or a blocking verdict, and says why', async () => {
    const vide = monter({ reponse: apercu({ nombreGeneres: 0, creneaux: [], coupures: [], joursSansFenetre: ['2026-07-06', '2026-07-07'] }) });
    await vide.fixture.whenStable();
    bouton(vide.fixture, 'Prévisualiser').click();
    await vide.fixture.whenStable();
    expect(racine(vide.fixture).textContent).toContain('rien à dériver');
    expect(bouton(vide.fixture, 'Écrire la grille').disabled).toBe(true);

    const bloquee = monter({
      reponse: apercu({ controle: { mode: 'AMPLITUDES', nombreCreneaux: 4, anomalies: [{ severite: 'ERREUR', type: 'DOUBLON', date: '2026-07-06', message: 'Doublon' }], ouvertures: [], faisabilite: null } })
    });
    await bloquee.fixture.whenStable();
    bouton(bloquee.fixture, 'Prévisualiser').click();
    await bloquee.fixture.whenStable();
    expect(racine(bloquee.fixture).textContent).toContain('Remplacer');
    expect(bouton(bloquee.fixture, 'Écrire la grille').disabled).toBe(true);
  });

  it('refuses an empty range or a missing closing time before calling the server', async () => {
    const { fixture, post } = monter({ dates: [null, null] });
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.field-error')!.textContent).toContain('première et la dernière date');
    expect(bouton(fixture, 'Prévisualiser').disabled).toBe(true);
    expect(post).not.toHaveBeenCalled();
  });
});
