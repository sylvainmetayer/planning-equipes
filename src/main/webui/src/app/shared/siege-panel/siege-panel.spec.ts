// The Siège panel over mocked services: what it shows of a seat, its two
// « et si » gestures — « Simuler un changement » and « Expliquer » — and the
// writes beside them, on a held seat and on an empty one, what each one calls,
// what a lecteur may do, and what it says is left to do afterwards. The words themselves are
// `seat.spec.ts`'s; the bench dialog is `bench-dialog.spec.ts`'s.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { PostesApi } from '../../core/api/postes-api';
import { JourJService } from '../../core/jour-j.service';
import {
  Animateur,
  Creneau,
  PlanningEvenement,
  PosteAffectation,
  Stand,
  VerrouillagePlanning,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SessionStore } from '../../core/session.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { fakeOf } from '../../core/testing/fake';
import { ConfirmData, ConfirmOption, ConfirmService } from '../confirm-dialog';
import { SeatPanel } from './siege-panel';

const TIR: Stand = {
  id: 'tir',
  nom: 'Tir à l’arc',
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
};
const MATIN: Creneau = {
  id: 1,
  jour: 1,
  date: '2026-07-18',
  heureDebut: '10:00:00',
  heureFin: '12:00:00',
};
const alice = { id: 'a1', prenom: 'Alice', nom: 'Martin' } as Animateur;
const bruno = { id: 'b1', prenom: 'Bruno', nom: 'Petit' } as Animateur;

function planning(): PlanningEvenement {
  const postes: PosteAffectation[] = [
    { id: 'P1', stand: TIR, creneau: MATIN, animateur: alice },
    { id: 'P2', stand: TIR, creneau: MATIN, animateur: null },
  ];
  return { postes, animateurs: [alice, bruno], score: null };
}

const ZERO = { hardScore: 0, mediumScore: 0, softScore: 0 };

/** The search of « Simuler un changement » on `posteId`, Bruno its one candidate. */
function suggestionsFor(posteId: string, holder: string | null) {
  return {
    posteId,
    animateurActuelId: holder,
    scoreAvant: { hardScore: -1, mediumScore: 0, softScore: 0 },
    contraintesVioleesAvant: [],
    candidatsEligibles: 1,
    depuis: 0,
    candidatsEvalues: 1,
    plafond: 20,
    suggestions: [
      {
        animateurId: 'b1',
        scoreApres: { hardScore: 0, mediumScore: -2, softScore: 0 },
        delta: { hardScore: 1, mediumScore: -2, softScore: 0 },
        violationsResolues: [],
        violationsIntroduites: [],
      },
    ],
  };
}

function button(root: HTMLElement, text: string): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((candidate) =>
    candidate.textContent?.includes(text),
  );
  if (!found) {
    throw new Error(`no button « ${text} »`);
  }
  return found;
}

describe('SeatPanel', () => {
  let fixture: ComponentFixture<SeatPanel>;
  const locks = signal<VerrouillagePlanning[]>([]);
  const editingLocked = signal(false);
  const postesApi = {
    place: vi.fn(),
    explanation: vi.fn(),
    applyRepair: vi.fn(),
    move: vi.fn(),
    simulate: vi.fn(),
    suggestions: vi.fn(),
  };
  const jourJ = { marquerAbsent: vi.fn() };
  const lectureSeule = signal(false);
  const verrous = fakeOf<VerrouillageStore>({
    verrouillages: locks,
    reload: () => Promise.resolve(undefined),
    create: () => Promise.resolve([]),
    remove: () => Promise.resolve(undefined),
    isStandVerrouille: () => false,
    isAnimateurCreneauVerrouille: () => false,
    estJourVerrouille: () => false,
  });
  const jobs = {
    editingLocked,
    solverBusy: signal(false),
    submitSolveIncremental: vi.fn(() => Promise.resolve({})),
  };
  const dialog = { open: vi.fn() };
  const confirm = {
    ask: vi.fn(() => Promise.resolve(true)),
    askWithOption: vi.fn(
      (_data: ConfirmData & { option: ConfirmOption }): Promise<{ checked: boolean } | null> =>
        Promise.resolve({
          checked: true,
        }),
    ),
  };

  beforeEach(() => {
    locks.set([]);
    editingLocked.set(false);
    lectureSeule.set(false);
    for (const mock of [
      ...Object.values(postesApi),
      verrous.create,
      verrous.remove,
      jobs.submitSolveIncremental,
      dialog.open,
      confirm.ask,
      confirm.askWithOption,
      jourJ.marquerAbsent,
    ]) {
      mock.mockReset();
    }
    confirm.ask.mockResolvedValue(true);
    confirm.askWithOption.mockResolvedValue({ checked: true });
    verrous.create.mockResolvedValue([]);
    postesApi.suggestions.mockImplementation((posteId: string) =>
      Promise.resolve(suggestionsFor(posteId, posteId === 'P1' ? 'a1' : null)),
    );
    postesApi.explanation.mockResolvedValue({
      posteId: 'P1',
      score: ZERO,
      contraintesViolees: [
        {
          name: 'equilibrerCharge',
          niveau: 'MEDIUM',
          categorie: 'Qualité',
          description: null,
          matchCount: 1,
          details: [],
        },
      ],
      contraintesRespectees: [],
    });
  });

  async function mount(posteId: string): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PostesApi, useValue: postesApi },
        {
          provide: ConstraintsApi,
          useValue: {
            catalogue: vi.fn(() =>
              Promise.resolve({
                contraintes: [{ name: 'equilibrerCharge', libelleCourt: 'Charge équilibrée' }],
              }),
            ),
          },
        },
        { provide: VerrouillageStore, useValue: verrous },
        {
          provide: ReferenceDataStore,
          useValue: { reload: vi.fn(() => Promise.resolve(undefined)) },
        },
        { provide: SolverJobService, useValue: jobs },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: JourJService, useValue: jourJ },
        { provide: SessionStore, useValue: { lectureSeule } },
      ],
    });
    fixture = TestBed.createComponent(SeatPanel);
    fixture.componentRef.setInput('planning', planning());
    fixture.componentRef.setInput('posteId', posteId);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  /** « Simuler un changement » opened, its suggestions read. */
  async function openSimulation(root: HTMLElement): Promise<void> {
    button(root, 'Simuler un changement').click();
    await vi.waitFor(() => expect(postesApi.suggestions).toHaveBeenCalled());
    await fixture.whenStable();
  }

  function byLabel(root: HTMLElement, label: string): HTMLButtonElement {
    return root.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
  }

  it('is a labelled side panel naming the stand, the time and the holder', async () => {
    const root = await mount('P1');

    const aside = root.querySelector('aside')!;
    const title = root.querySelector('h2')!;
    expect(aside.getAttribute('aria-labelledby')).toBe(title.id);
    expect(title.textContent).toContain('Tir à l’arc');
    expect(root.textContent).toContain('10:00–12:00');
    expect(root.textContent).toContain('Alice Martin');
  });

  it('explains the held seat on « Expliquer », in the words of the catalogue, never a Java name', async () => {
    const root = await mount('P1');
    // Nothing read before the gesture is asked for.
    expect(postesApi.explanation).not.toHaveBeenCalled();

    button(root, 'Expliquer').click();
    await vi.waitFor(() => expect(root.textContent).toContain('Charge équilibrée'));

    expect(root.textContent).not.toContain('equilibrerCharge');
    expect(postesApi.explanation).toHaveBeenCalledTimes(1);
    // The persisted plan read under the edition's rules, never the copy on screen.
    expect(postesApi.explanation).toHaveBeenCalledWith('P1');
  });

  it('offers the two gestures of a held seat and the writes beside them, the fiche among them', async () => {
    const root = await mount('P1');

    for (const label of [
      'Simuler un changement',
      'Expliquer',
      'Libérer',
      'Marquer absent',
      'Verrouiller',
    ]) {
      expect(button(root, label).disabled).toBe(false);
    }
    expect(root.querySelector('a[href="/animateurs/a1"]')).not.toBeNull();
  });

  // The move dialog no longer depends on the drag-and-drop flag: nothing here
  // reads the application's configuration.
  it('simulates a move chosen in the dialog of the day views, applies it, then asks to repair the hole it left', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ source: null, target: 'P2' }) });
    const moved = {
      posteSourceId: 'P1',
      posteCibleId: 'P2',
      animateurSourceId: 'a1',
      animateurCibleId: null,
      scoreAvant: ZERO,
      scoreApres: ZERO,
      delta: ZERO,
      casseContrainteDure: false,
      nouvellesViolationsDures: [],
    };
    postesApi.simulate.mockResolvedValue(moved);
    postesApi.move.mockResolvedValue(moved);
    const root = await mount('P1');
    let changed = 0;
    fixture.componentInstance.planChanged.subscribe(() => changed++);

    await openSimulation(root);
    button(root, 'Vers un autre siège').click();
    await vi.waitFor(() =>
      expect(postesApi.simulate).toHaveBeenCalledWith('P1', { posteId: 'P2' }),
    );
    await fixture.whenStable();
    // Before and after, nothing written yet.
    expect(root.textContent).toContain('Score avant : 0/0/0 · après : 0/0/0');
    expect(postesApi.move).not.toHaveBeenCalled();

    button(root, 'Appliquer').click();
    await vi.waitFor(() =>
      expect(postesApi.move).toHaveBeenCalledWith('P1', { posteId: 'P2' }, 'a1'),
    );
    await fixture.whenStable();

    expect(changed).toBe(1);
    expect(root.querySelector('a[href="/publication"]')?.textContent).toContain('Prévenir');
    expect(button(root, 'Corriger le reste')).toBeTruthy();
  });

  it('frees the seat of the person shown once confirmed, keeps them off it, and « Corriger le reste » starts an incremental solve', async () => {
    postesApi.applyRepair.mockResolvedValue(undefined);
    const root = await mount('P1');

    button(root, 'Libérer').click();
    // The holder shown travels as the precondition: somebody else there by now is a 409.
    await vi.waitFor(() => expect(postesApi.applyRepair).toHaveBeenCalledWith('P1', null, 'a1'));
    // Ticked by default: the lock an accepted échange lays on whoever it frees.
    await vi.waitFor(() =>
      expect(verrous.create).toHaveBeenCalledWith({
        type: 'ANIMATEUR_CRENEAU',
        animateurId: 'a1',
        creneauId: 1,
      }),
    );
    expect(confirm.askWithOption.mock.calls[0][0].option.checked).toBe(true);
    await fixture.whenStable();
    expect(root.textContent).toContain("restera à l'écart");
    button(root, 'Corriger le reste').click();

    await vi.waitFor(() =>
      expect(jobs.submitSolveIncremental).toHaveBeenCalledWith(
        { animateurIds: [], jours: [], standIds: [] },
        undefined,
        false,
      ),
    );
  });

  it('frees without a lock when the box was unticked, and not at all when cancelled', async () => {
    postesApi.applyRepair.mockResolvedValue(undefined);
    confirm.askWithOption.mockResolvedValueOnce({ checked: false });
    const root = await mount('P1');

    button(root, 'Libérer').click();
    await vi.waitFor(() => expect(postesApi.applyRepair).toHaveBeenCalledTimes(1));
    await fixture.whenStable();
    expect(verrous.create).not.toHaveBeenCalled();

    confirm.askWithOption.mockResolvedValueOnce(null);
    button(root, 'Libérer').click();
    await fixture.whenStable();
    expect(postesApi.applyRepair).toHaveBeenCalledTimes(1);
  });

  // #711 review: the search reads the persisted plan prepared server-side,
  // and the write carries the holder shown as its precondition.
  it('simulates a suggestion before and after, and hands the seat over from the holder shown', async () => {
    postesApi.applyRepair.mockResolvedValue(undefined);
    const root = await mount('P1');

    await openSimulation(root);
    expect(postesApi.suggestions).toHaveBeenCalledWith('P1');
    byLabel(root, 'Simuler : Bruno Petit sur ce siège').click();
    await fixture.whenStable();
    expect(root.textContent).toContain('Donner ce siège à Bruno Petit');
    expect(root.textContent).toContain('Score avant : -1/0/0 · après : 0/-2/0');
    expect(postesApi.applyRepair).not.toHaveBeenCalled();

    button(root, 'Appliquer').click();
    await vi.waitFor(() => expect(postesApi.applyRepair).toHaveBeenCalledWith('P1', 'b1', 'a1'));
    await fixture.whenStable();
    expect(root.textContent).toContain('perd 2');
  });

  it('shows a move that would break a hard rule as refused, with nothing to apply', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ source: null, target: 'P2' }) });
    postesApi.simulate.mockResolvedValue({
      posteSourceId: 'P1',
      posteCibleId: 'P2',
      animateurSourceId: 'a1',
      animateurCibleId: null,
      scoreAvant: ZERO,
      scoreApres: { hardScore: -1, mediumScore: 0, softScore: 0 },
      delta: { hardScore: -1, mediumScore: 0, softScore: 0 },
      casseContrainteDure: true,
      nouvellesViolationsDures: [
        { name: 'reposQuotidien', description: 'Repos quotidien', matchesSupplementaires: 1 },
      ],
    });
    const root = await mount('P1');

    await openSimulation(root);
    button(root, 'Vers un autre siège').click();
    await vi.waitFor(() => expect(root.textContent).toContain('Refusé'));

    expect(root.textContent).toContain('Repos quotidien');
    expect(() => button(root, 'Appliquer')).toThrow();
  });

  // ADR 0099: simulating and explaining are reads; applying is a write.
  it('lets a lecteur simulate and explain, and apply nothing', async () => {
    lectureSeule.set(true);
    const root = await mount('P1');

    expect(button(root, 'Expliquer').disabled).toBe(false);
    expect(button(root, 'Libérer').disabled).toBe(true);
    await openSimulation(root);
    byLabel(root, 'Simuler : Bruno Petit sur ce siège').click();
    await fixture.whenStable();

    expect(root.textContent).toContain('Donner ce siège à Bruno Petit');
    expect(() => button(root, 'Appliquer')).toThrow();
  });

  it('says what the server wants read about a lock it laid', async () => {
    verrous.create.mockResolvedValue([
      { type: 'VERROUILLAGE_SUR_VIOLATION_DURE', message: 'Un siège gelé casse une règle dure.' },
    ]);
    const root = await mount('P1');

    button(root, 'Verrouiller').click();

    await vi.waitFor(() =>
      expect(root.textContent).toContain('Un siège gelé casse une règle dure.'),
    );
  });

  // A held seat reads its bench too — who could take over — without « Placer ».
  it('opens the bench of a held seat read-only, and places nobody from it', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ animateurId: 'b1', keep: true }) });
    const root = await mount('P1');

    await openSimulation(root);
    button(root, 'Voir tout le banc').click();
    await fixture.whenStable();

    expect(dialog.open.mock.calls[0][1].data.offerPlacement).toBe(false);
    expect(postesApi.place).not.toHaveBeenCalled();
  });

  /** The gesture of Aujourd'hui, from the seat: the same service, on this timeslot alone. */
  it('marks the holder absent on this timeslot, then proposes to warn and repair', async () => {
    jourJ.marquerAbsent.mockResolvedValue({
      animateurId: 'a1',
      nomAffiche: 'Alice Martin',
      entrees: [],
      postesLiberes: [{ posteId: 'P1' }],
    });
    const root = await mount('P1');

    button(root, 'Marquer absent').click();
    await vi.waitFor(() =>
      expect(jourJ.marquerAbsent).toHaveBeenCalledWith('a1', '', '2026-07-18', undefined, 1),
    );
    await fixture.whenStable();

    expect(root.textContent).toContain('marqué absent');
    expect(root.querySelector('a[href="/publication"]')?.textContent).toContain('Prévenir');
    expect(button(root, 'Corriger le reste')).toBeTruthy();
  });

  it('locks this person on this timeslot, and unlocks only that lock', async () => {
    const root = await mount('P1');

    button(root, 'Verrouiller').click();
    await vi.waitFor(() =>
      expect(verrous.create).toHaveBeenCalledWith({
        type: 'ANIMATEUR_CRENEAU',
        animateurId: 'a1',
        creneauId: 1,
      }),
    );

    locks.set([
      {
        id: 'L1',
        type: 'ANIMATEUR_CRENEAU',
        animateurId: 'a1',
        standId: null,
        creneauId: 1,
        jour: null,
        raison: null,
      },
    ]);
    await fixture.whenStable();
    // A lock holds the seat: the server would refuse any write, so the panel says so first.
    expect(button(root, 'Libérer').disabled).toBe(true);
    button(root, 'Déverrouiller').click();
    await vi.waitFor(() => expect(verrous.remove).toHaveBeenCalledWith('L1'));
    // A lock rewrites nothing the animateurs read: nothing to announce.
    expect(root.querySelector('a[href="/publication"]')).toBeNull();
  });

  it('places somebody on an empty seat from the bench, and keeps them there by a lock', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ animateurId: 'b1', keep: true }) });
    postesApi.place.mockResolvedValue({
      posteSourceId: 'P2',
      posteCibleId: null,
      animateurSourceId: null,
      animateurCibleId: 'b1',
      scoreAvant: ZERO,
      scoreApres: { hardScore: 1, mediumScore: -2, softScore: 0 },
      delta: { hardScore: 1, mediumScore: -2, softScore: 0 },
      casseContrainteDure: false,
      nouvellesViolationsDures: [],
    });
    const root = await mount('P2');
    expect(root.textContent).toContain('Place vide');
    expect(() => button(root, 'Expliquer')).toThrow();

    await openSimulation(root);
    button(root, 'Voir tout le banc').click();
    expect(dialog.open.mock.calls[0][1].data.offerPlacement).toBe(true);
    await vi.waitFor(() => expect(postesApi.place).toHaveBeenCalledWith('P2', 'b1'));
    await vi.waitFor(() =>
      expect(verrous.create).toHaveBeenCalledWith({
        type: 'ANIMATEUR_CRENEAU',
        animateurId: 'b1',
        creneauId: 1,
      }),
    );
    await fixture.whenStable();

    expect(root.textContent).toContain('Bruno Petit');
    // Accepted, but the medium level lost two points: said, not hidden.
    expect(root.textContent).toContain('perd 2');
    expect(root.querySelector('a[href="/publication"]')).not.toBeNull();
    expect(() => button(root, 'Corriger le reste')).toThrow();
  });

  it('places without a lock when the box was unticked', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ animateurId: 'b1', keep: false }) });
    postesApi.place.mockResolvedValue({ delta: ZERO, animateurSourceId: null });
    const root = await mount('P2');

    await openSimulation(root);
    button(root, 'Voir tout le banc').click();
    await vi.waitFor(() => expect(postesApi.place).toHaveBeenCalled());
    await fixture.whenStable();

    expect(verrous.create).not.toHaveBeenCalled();
  });

  it('words a refusal of the server in the panel', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ animateurId: 'b1', keep: true }) });
    postesApi.place.mockRejectedValue(new Error('Affectation refusée : repos quotidien.'));
    const root = await mount('P2');

    await openSimulation(root);
    button(root, 'Voir tout le banc').click();

    await vi.waitFor(() => expect(root.textContent).toContain('Affectation refusée'));
    expect(verrous.create).not.toHaveBeenCalled();
  });

  it('places a simulated suggestion on an empty seat, and keeps it by a lock unless unticked', async () => {
    postesApi.place.mockResolvedValue({ delta: ZERO, animateurSourceId: null });
    const root = await mount('P2');

    await openSimulation(root);
    byLabel(root, 'Simuler : Bruno Petit sur ce siège').click();
    await fixture.whenStable();
    expect(root.textContent).toContain('Placer Bruno Petit sur ce siège');
    root.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await fixture.whenStable();
    button(root, 'Appliquer').click();

    await vi.waitFor(() => expect(postesApi.place).toHaveBeenCalledWith('P2', 'b1'));
    await fixture.whenStable();
    expect(verrous.create).not.toHaveBeenCalled();
  });

  it('simulates during a running solve, and waits for it before any write', async () => {
    editingLocked.set(true);
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    const root = await mount('P2');

    await openSimulation(root);
    button(root, 'Voir tout le banc').click();
    expect(dialog.open.mock.calls[0][1].data.offerPlacement).toBe(false);
    byLabel(root, 'Simuler : Bruno Petit sur ce siège').click();
    await fixture.whenStable();
    expect(button(root, 'Appliquer').disabled).toBe(true);
    expect(root.textContent).toContain('Une résolution est en cours');
  });

  it('closes on Escape, from the panel itself', async () => {
    const root = await mount('P1');
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);

    root
      .querySelector('aside')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(closed).toBe(1);
  });
});
