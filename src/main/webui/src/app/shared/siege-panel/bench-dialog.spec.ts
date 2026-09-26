// « Qui peut tenir ce siège ? » — the bench, once a Diagnostic tab choosing its
// own seat, now a dialog asked about the seat the reader pointed at. Pinned
// here: it asks the server about that very seat, keeps the few who can take it
// in front of the many who cannot, never shows a rule's Java name, and closes
// with the person chosen and whether to keep them there.
// `bench.spec.ts` covers the pure rows.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { Animateur, BancDeTouche, ConstraintView, VerrouillagePlanning } from '../../core/models';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { BenchDialog, BenchDialogData } from './bench-dialog';

function animateur(id: string, prenom: string): Animateur {
  return {
    id,
    prenom,
    nom: 'X',
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

function bench(partial: Partial<BancDeTouche> = {}): BancDeTouche {
  return {
    creneauId: 5,
    statut: 'EVALUATED',
    posteCibleId: 'P1',
    standCibleId: 'tir',
    animateurCibleId: null,
    seatStarted: false,
    total: 3,
    disponibles: 1,
    creneauxAvecSieges: [],
    animateurs: [
      {
        animateurId: 'a1',
        disponible: true,
        degradeLePlan: false,
        delta: { hardScore: 1, mediumScore: 0, softScore: 0 },
        motifs: [],
      },
      {
        animateurId: 'a2',
        disponible: false,
        degradeLePlan: false,
        delta: { hardScore: 0, mediumScore: 0, softScore: 0 },
        motifs: [
          {
            contrainte: 'equilibrerCharge',
            niveau: 'MEDIUM',
            categorie: 'Qualité',
            description: 'Répartir les heures équitablement.',
          },
        ],
      },
      {
        animateurId: 'a3',
        disponible: false,
        degradeLePlan: true,
        delta: { hardScore: -1, mediumScore: 0, softScore: 0 },
        motifs: [
          {
            contrainte: 'animateurDisponible',
            niveau: 'HARD',
            categorie: 'Disponibilité',
            description: "L'animateur doit être disponible.",
          },
        ],
      },
    ],
    ...partial,
  };
}

const catalogue = new Map<string, ConstraintView>([
  [
    'equilibrerCharge',
    { name: 'equilibrerCharge', libelleCourt: 'Charge équilibrée' } as ConstraintView,
  ],
  [
    'animateurDisponible',
    { name: 'animateurDisponible', libelleCourt: 'Disponible ce jour-là' } as ConstraintView,
  ],
]);

describe('BenchDialog', () => {
  const analysesApi = { bench: vi.fn() };
  const close = vi.fn();
  const locks = signal<VerrouillagePlanning[]>([]);
  let fixture: ComponentFixture<BenchDialog>;

  beforeEach(() => {
    analysesApi.bench.mockReset();
    close.mockReset();
    locks.set([]);
  });

  async function open(
    answer: () => Promise<BancDeTouche>,
    offerPlacement = true,
  ): Promise<HTMLElement> {
    analysesApi.bench.mockImplementation(answer);
    const data: BenchDialogData = {
      posteId: 'P1',
      creneauId: 5,
      standId: 'tir',
      title: 'Tir · Jour 1',
      animateurs: [animateur('a1', 'Alice'), animateur('a2', 'Bruno'), animateur('a3', 'Chloé')],
      catalogue,
      offerPlacement,
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: VerrouillageStore, useValue: { verrouillages: locks } },
      ],
    });
    fixture = TestBed.createComponent(BenchDialog);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('asks the server about the very seat it was opened on', async () => {
    await open(async () => bench());

    expect(analysesApi.bench).toHaveBeenCalledExactlyOnceWith(5, 'tir', 'P1');
  });

  it('lists who can take the seat first, and folds those a hard rule keeps out', async () => {
    const root = await open(async () => bench());

    const names = Array.from(root.querySelectorAll('.bench-line strong')).map((node) =>
      node.textContent?.trim(),
    );
    expect(names).toEqual(['Alice X', 'Bruno X']);
    const more = Array.from(root.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('autres'),
    )!;
    expect(more.textContent).toContain('1');
    more.click();
    await fixture.whenStable();
    expect(root.querySelectorAll('.bench-line')).toHaveLength(3);
  });

  it('names the rules by their short label, never by their Java name', async () => {
    const root = await open(async () => bench());
    Array.from(root.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('autres'))!
      .click();
    await fixture.whenStable();

    const chips = Array.from(root.querySelectorAll('.bench-reason')).map((chip) =>
      chip.textContent?.trim(),
    );
    expect(chips).toEqual(['Charge équilibrée', 'Disponible ce jour-là']);
    expect(root.textContent).not.toContain('equilibrerCharge');
    expect(root.textContent).not.toContain('animateurDisponible');
  });

  it('offers « Placer » on the available lines only, and closes with the choice kept', async () => {
    const root = await open(async () => bench());

    const place = root.querySelectorAll<HTMLButtonElement>('.bench-place');
    expect(place).toHaveLength(1);
    place[0].click();

    expect(close).toHaveBeenCalledExactlyOnceWith({ animateurId: 'a1', keep: true });
  });

  it('closes without the lock when « la garder au prochain calcul » is unticked', async () => {
    const root = await open(async () => bench());

    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await fixture.whenStable();
    root.querySelector<HTMLButtonElement>('.bench-place')!.click();

    expect(close).toHaveBeenCalledExactlyOnceWith({ animateurId: 'a1', keep: false });
  });

  it('shows the refusal of the server rather than an empty list', async () => {
    const root = await open(async () => {
      throw new Error('Créneau inconnu.');
    });

    await vi.waitFor(() => expect(root.textContent).toContain('Créneau inconnu.'));
  });

  it('says why there is nobody to list, as an answer and not an error', async () => {
    const root = await open(async () => bench({ statut: 'NO_PLAN', animateurs: [] }));

    expect(root.textContent).toContain('Aucun planning enregistré');
    expect(root.querySelector('.bench-place')).toBeNull();
  });

  // #711 review: the write refuses a person a lock keeps off the timeslot, so
  // the line says so instead of offering a click that meets the refusal.
  it('offers no « Placer » to a person a lock keeps off this timeslot, and says why', async () => {
    locks.set([
      {
        id: 'L1',
        type: 'ANIMATEUR_CRENEAU',
        animateurId: 'a1',
        standId: null,
        creneauId: 5,
        jour: null,
        raison: null,
      },
    ]);
    const root = await open(async () => bench());

    expect(root.querySelector('.bench-place')).toBeNull();
    expect(root.querySelector('.bench-blocked')?.textContent).toContain('Verrouillé');
  });

  it('places nobody on a timeslot already started, and says so once', async () => {
    const root = await open(async () => bench({ seatStarted: true }));

    expect(root.querySelector('.bench-place')).toBeNull();
    expect(root.querySelector('mat-checkbox')).toBeNull();
    expect(root.textContent).toContain('déjà commencé');
  });

  // A held seat: the bench answers « qui pourrait le remplacer ? », read-only.
  it('reads the bench of a held seat without placing anybody', async () => {
    const root = await open(async () => bench({ animateurCibleId: 'a2' }), false);

    expect(root.textContent).toContain('Ce siège est tenu par Bruno X');
    expect(root.querySelector('.bench-place')).toBeNull();
    expect(root.querySelector('mat-checkbox')).toBeNull();
    expect(root.querySelectorAll('.bench-line').length).toBeGreaterThan(0);
  });
});
