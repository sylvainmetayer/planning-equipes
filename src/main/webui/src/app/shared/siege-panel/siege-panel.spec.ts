// The Siège panel over mocked services: what it shows of a seat, which
// gestures it offers on a held seat and on an empty one, what each one calls,
// and what it says is left to do afterwards. The words themselves are
// `seat.spec.ts`'s; the bench dialog is `bench-dialog.spec.ts`'s.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
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
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { ConfirmData, ConfirmOption, ConfirmService } from '../confirm-dialog';
import { SeatPanel } from './siege-panel';

const TIR = { id: 'tir', nom: 'Tir à l’arc', typologiesProposees: [] } as unknown as Stand;
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
  return { postes, animateurs: [alice, bruno] } as unknown as PlanningEvenement;
}

const ZERO = { hardScore: 0, mediumScore: 0, softScore: 0 };

describe('SeatPanel', () => {
  let fixture: ComponentFixture<SeatPanel>;
  const locks = signal<VerrouillagePlanning[]>([]);
  const editingLocked = signal(false);
  const explanations = {
    suggererReparations: vi.fn(),
    applyRepair: vi.fn(),
    deplacer: vi.fn(),
  };
  const postesApi = { place: vi.fn(), explanation: vi.fn() };
  const jourJ = { suggestions: vi.fn(), marquerAbsent: vi.fn() };
  const verrous = {
    verrouillages: locks,
    reload: vi.fn(async () => undefined),
    create: vi.fn(async () => []),
    remove: vi.fn(async () => undefined),
    estStandVerrouille: () => false,
    estCreneauVerrouille: () => false,
    estJourVerrouille: () => false,
  };
  const jobs = {
    editingLocked,
    solverBusy: signal(false),
    submitSolveIncremental: vi.fn(async () => ({})),
  };
  const dialog = { open: vi.fn() };
  const confirm = {
    ask: vi.fn(async () => true),
    askWithOption: vi.fn(
      async (
        _data: ConfirmData & { option: ConfirmOption },
      ): Promise<{ checked: boolean } | null> => ({
        checked: true,
      }),
    ),
  };

  beforeEach(() => {
    locks.set([]);
    editingLocked.set(false);
    for (const mock of [
      ...Object.values(explanations),
      ...Object.values(postesApi),
      verrous.create,
      verrous.remove,
      jobs.submitSolveIncremental,
      dialog.open,
      confirm.ask,
      confirm.askWithOption,
      jourJ.suggestions,
      jourJ.marquerAbsent,
    ]) {
      mock.mockReset();
    }
    confirm.ask.mockResolvedValue(true);
    confirm.askWithOption.mockResolvedValue({ checked: true });
    verrous.create.mockResolvedValue([]);
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
        { provide: AffectationExplanationService, useValue: explanations },
        { provide: PostesApi, useValue: postesApi },
        {
          provide: ConstraintsApi,
          useValue: {
            catalogue: vi.fn(async () => ({
              contraintes: [{ name: 'equilibrerCharge', libelleCourt: 'Charge équilibrée' }],
            })),
          },
        },
        { provide: VerrouillageStore, useValue: verrous },
        { provide: ReferenceDataStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverJobService, useValue: jobs },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: JourJService, useValue: jourJ },
      ],
    });
    fixture = TestBed.createComponent(SeatPanel);
    fixture.componentRef.setInput('planning', planning());
    fixture.componentRef.setInput('posteId', posteId);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
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

  it('is a labelled side panel naming the stand, the time and the holder', async () => {
    const root = await mount('P1');

    const aside = root.querySelector('aside')!;
    const title = root.querySelector('h2')!;
    expect(aside.getAttribute('aria-labelledby')).toBe(title.id);
    expect(title.textContent).toContain('Tir à l’arc');
    expect(root.textContent).toContain('10:00–12:00');
    expect(root.textContent).toContain('Alice Martin');
  });

  it('explains the held seat in the words of the catalogue, never a Java name', async () => {
    const root = await mount('P1');
    await vi.waitFor(() => expect(root.textContent).toContain('Charge équilibrée'));

    expect(root.textContent).not.toContain('equilibrerCharge');
    expect(postesApi.explanation).toHaveBeenCalledTimes(1);
    // The persisted plan read under the edition's rules, never the copy on screen.
    expect(postesApi.explanation).toHaveBeenCalledWith('P1');
  });

  it('offers the gestures of a held seat, the fiche among them', async () => {
    const root = await mount('P1');

    for (const label of [
      'Remplacer',
      'Déplacer vers',
      'Libérer',
      'Verrouiller',
      'Qui peut tenir',
    ]) {
      expect(button(root, label).disabled).toBe(false);
    }
    expect(root.querySelector('a[href="/animateurs/a1"]')).not.toBeNull();
  });

  // The move dialog no longer depends on the drag-and-drop flag: nothing here
  // reads the application's configuration.
  it('moves through the dialog of the day views, then asks to repair the hole it left', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ source: null, target: 'P2' }) });
    explanations.deplacer.mockResolvedValue({
      posteSourceId: 'P1',
      posteCibleId: 'P2',
      animateurSourceId: 'a1',
      animateurCibleId: null,
      scoreAvant: ZERO,
      scoreApres: ZERO,
      delta: ZERO,
      casseContrainteDure: false,
      nouvellesViolationsDures: [],
    });
    const root = await mount('P1');
    let changed = 0;
    fixture.componentInstance.planChanged.subscribe(() => changed++);

    button(root, 'Déplacer vers').click();
    await vi.waitFor(() =>
      expect(explanations.deplacer).toHaveBeenCalledWith('P1', { posteId: 'P2' }, 'a1'),
    );
    await fixture.whenStable();

    expect(changed).toBe(1);
    expect(root.querySelector('a[href="/publication"]')?.textContent).toContain('Prévenir');
    expect(button(root, 'Corriger le reste')).toBeTruthy();
  });

  it('frees the seat of the person shown once confirmed, keeps them off it, and « Corriger le reste » starts an incremental solve', async () => {
    explanations.applyRepair.mockResolvedValue(undefined);
    const root = await mount('P1');

    button(root, 'Libérer').click();
    // The holder shown travels as the precondition: somebody else there by now is a 409.
    await vi.waitFor(() => expect(explanations.applyRepair).toHaveBeenCalledWith('P1', null, 'a1'));
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
    explanations.applyRepair.mockResolvedValue(undefined);
    confirm.askWithOption.mockResolvedValueOnce({ checked: false });
    const root = await mount('P1');

    button(root, 'Libérer').click();
    await vi.waitFor(() => expect(explanations.applyRepair).toHaveBeenCalledTimes(1));
    await fixture.whenStable();
    expect(verrous.create).not.toHaveBeenCalled();

    confirm.askWithOption.mockResolvedValueOnce(null);
    button(root, 'Libérer').click();
    await fixture.whenStable();
    expect(explanations.applyRepair).toHaveBeenCalledTimes(1);
  });

  // #711 review: the search reads the persisted plan prepared server-side,
  // and the write carries the holder shown as its precondition.
  it('searches the replacements on the persisted plan, and hands the seat over from the holder shown', async () => {
    jourJ.suggestions.mockResolvedValue({
      posteId: 'P1',
      animateurActuelId: 'a1',
      scoreAvant: ZERO,
      contraintesVioleesAvant: [],
      candidatsEligibles: 1,
      candidatsEvalues: 1,
      plafond: 20,
      suggestions: [
        {
          animateurId: 'b1',
          scoreApres: ZERO,
          delta: ZERO,
          violationsResolues: [],
          violationsIntroduites: [],
        },
      ],
    });
    explanations.applyRepair.mockResolvedValue(undefined);
    const root = await mount('P1');

    button(root, 'Remplacer').click();
    await vi.waitFor(() => expect(jourJ.suggestions).toHaveBeenCalledWith('P1'));
    await fixture.whenStable();
    expect(explanations.suggererReparations).not.toHaveBeenCalled();
    root
      .querySelector<HTMLButtonElement>('button[aria-label="Mettre Bruno Petit sur ce siège"]')!
      .click();

    await vi.waitFor(() => expect(explanations.applyRepair).toHaveBeenCalledWith('P1', 'b1', 'a1'));
  });

  it('says what the server wants read about a lock it laid', async () => {
    verrous.create.mockResolvedValue([
      { type: 'VERROU_SIEGE_EN_DEFAUT', message: 'Un siège gelé casse une règle dure.' },
    ] as never);
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

    button(root, 'Qui peut tenir ce siège').click();
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
    expect(button(root, 'Remplacer').disabled).toBe(true);
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
    expect(postesApi.explanation).not.toHaveBeenCalled();

    button(root, 'Qui peut tenir ce siège').click();
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

    button(root, 'Qui peut tenir ce siège').click();
    await vi.waitFor(() => expect(postesApi.place).toHaveBeenCalled());
    await fixture.whenStable();

    expect(verrous.create).not.toHaveBeenCalled();
  });

  it('words a refusal of the server in the panel', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of({ animateurId: 'b1', keep: true }) });
    postesApi.place.mockRejectedValue(new Error('Affectation refusée : repos quotidien.'));
    const root = await mount('P2');

    button(root, 'Qui peut tenir ce siège').click();

    await vi.waitFor(() => expect(root.textContent).toContain('Affectation refusée'));
    expect(verrous.create).not.toHaveBeenCalled();
  });

  it('waits for a running solve before any write', async () => {
    editingLocked.set(true);
    const root = await mount('P2');

    expect(button(root, 'Qui peut tenir ce siège').disabled).toBe(true);
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
