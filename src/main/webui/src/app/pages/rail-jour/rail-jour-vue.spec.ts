// What `rail-jour.spec.ts` cannot see: which lines reach the screen, how the
// day is chosen, and whether the rail can be walked without a mouse. The rail
// is read on a day of tension, and the empty lines — who is still callable —
// are the reason it exists, so "the free animateur is on screen" is a rendering
// assertion, not a builder one.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import {
  Animateur,
  Creneau,
  PlanningEvenement,
  RapportPauses,
  PosteAffectation,
  Stand,
} from '../../core/models';
import { MatDialog } from '@angular/material/dialog';
import { RailJourView } from './rail-jour-vue';

function stand(id: string): Stand {
  return {
    id,
    nom: id,
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
  };
}

function animateur(prenom: string, overrides: Partial<Animateur> = {}): Animateur {
  return {
    id: prenom,
    prenom,
    nom: '',
    dateNaissance: '2000-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...overrides,
  };
}

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

const ALICE = animateur('Alice');
const BOB = animateur('Bob');
const CHLOE = animateur('Chloe', { joursIndisponibles: ['2026-08-01'] });

/** Alice works both days, Bob is free on day 1, Chloé declared day 1 unavailable. */
function planningDeuxJours(): PlanningEvenement {
  return {
    animateurs: [ALICE, BOB, CHLOE],
    postes: [
      poste({ id: 'p1', creneau: creneau({ id: 1 }), stand: stand('Tir'), animateur: ALICE }),
      poste({
        id: 'p2',
        creneau: creneau({ id: 2, jour: 2, date: '2026-08-02' }),
        stand: stand('Dixit'),
        animateur: ALICE,
      }),
      poste({
        id: 'p3',
        creneau: creneau({ id: 3, jour: 2, date: '2026-08-02' }),
        stand: stand('Tir'),
        animateur: BOB,
      }),
    ],
    score: null,
  };
}

describe('RailJourView', () => {
  let fixture: ComponentFixture<RailJourView>;

  /** Renders the view as the Journée page feeds it: the plan and the day as inputs, the breaks too. */
  async function rendre(
    evenement: PlanningEvenement,
    entrees: { jour?: number; filtre?: string; stand?: string; animateur?: string } = {},
    pauses: RapportPauses | null = null,
  ): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: AffectationExplanationService, useValue: { deplacer: vi.fn() } },
      ],
    });
    fixture = TestBed.createComponent(RailJourView);
    fixture.componentRef.setInput('planning', evenement);
    fixture.componentRef.setInput('pauses', pauses);
    for (const [cle, valeur] of Object.entries(entrees)) {
      fixture.componentRef.setInput(cle, valeur);
    }
    await fixture.whenStable();
  }

  async function changerJour(jour: number): Promise<void> {
    fixture.componentRef.setInput('jour', jour);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function noms(): string[] {
    return Array.from(racine().querySelectorAll('.rail-nom-label')).map((each) =>
      each.textContent!.trim(),
    );
  }

  function cellule(index: number): HTMLElement {
    return racine().querySelector(`[data-ligne="${index}"]`) as HTMLElement;
  }

  async function basculerVue(libelle: string): Promise<void> {
    const bouton = Array.from(racine().querySelectorAll('mat-button-toggle button')).find((each) =>
      each.textContent!.includes(libelle),
    ) as HTMLElement;
    bouton.click();
    await fixture.whenStable();
  }

  it('shows one line per animateur of the edition, the free ones included', async () => {
    await rendre(planningDeuxJours());

    expect(noms()).toEqual(['Alice', 'Bob', 'Chloe']);
    // Alice works, Bob is callable, Chloé is not: the three states are on screen.
    expect(racine().querySelectorAll('.rail-bloc')).toHaveLength(1);
    expect(racine().querySelectorAll('.rail-cell-libre')).toHaveLength(1);
    expect(racine().querySelectorAll('.rail-cell-indisponible')).toHaveLength(1);
  });

  // Same warning as the day calendar, on the other screen that carries the
  // gesture: it has to be there every time the page is opened.
  it('says on the page that dragging is still under test', async () => {
    await rendre(planningDeuxJours());

    expect(racine().querySelector('.essai-bandeau')?.textContent).toContain('en cours de test');
  });

  it('announces every line as text, since the rail itself is decorative', async () => {
    await rendre(planningDeuxJours());

    expect(cellule(0).getAttribute('aria-label')).toContain('Tir · 10:00 – 12:00');
    expect(cellule(1).getAttribute('aria-label')).toContain('mobilisable');
    expect(cellule(2).getAttribute('aria-label')).toContain('indisponible');
    expect(racine().querySelector('.rail-track')!.getAttribute('aria-hidden')).toBe('true');
  });

  it('opens on the first day and follows the day the page hands it', async () => {
    await rendre(planningDeuxJours());
    expect(racine().querySelectorAll('.rail-bloc')).toHaveLength(1);

    await changerJour(2);

    // Day 2: Alice and Bob work, Chloé's unavailability does not cover it.
    expect(racine().querySelectorAll('.rail-bloc')).toHaveLength(2);
    expect(racine().querySelectorAll('.rail-cell-indisponible')).toHaveLength(0);
  });

  it('falls back on the first day when the day it is handed no longer exists', async () => {
    await rendre(planningDeuxJours(), { jour: 2 });
    expect(racine().querySelectorAll('.rail-bloc')).toHaveLength(2);

    await rendre(planningDeuxJours(), { jour: 99 });
    expect(racine().querySelectorAll('.rail-bloc')).toHaveLength(1);
  });

  it('keeps only the lines the shared filters name: a stand, a person, a name', async () => {
    await rendre(planningDeuxJours(), { stand: 'Tir' });
    expect(noms()).toEqual(['Alice']);

    await rendre(planningDeuxJours(), { animateur: 'Bob' });
    expect(noms()).toEqual(['Bob']);

    await rendre(planningDeuxJours(), { filtre: 'chlo' });
    expect(noms()).toEqual(['Chloe']);
  });

  it('reads the lines param of an older link, and ignores what it does not know', () => {
    expect(RailJourView.readLignes('libres')).toBe('libres');
    expect(RailJourView.readLignes('affectes')).toBe('affectes');
    expect(RailJourView.readLignes('tout-le-monde')).toBe('tous');
    expect(RailJourView.readLignes(null)).toBe('tous');
  });

  it('narrows to the animateurs still callable', async () => {
    await rendre(planningDeuxJours());

    await basculerVue('Mobilisables');
    expect(noms()).toEqual(['Bob']);

    await basculerVue('Affectés');
    expect(noms()).toEqual(['Alice']);
  });

  // RGAA 7.3: the drag has no key of its own, so Enter on a line asks where
  // to move one of its shifts — only the lines a drop would accept.
  it('opens the move of a shift on Enter, offering the lines a drop would accept', async () => {
    await rendre(planningDeuxJours());
    const dialog = TestBed.inject(MatDialog);
    const open = vi.spyOn(dialog, 'open');
    const ligneAlice = Array.from(racine().querySelectorAll<HTMLElement>('[data-ligne]')).find(
      (each) => each.getAttribute('aria-label')!.includes('Alice'),
    )!;

    ligneAlice.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    expect(open).toHaveBeenCalledTimes(1);
    const { data } = open.mock.calls[0][1] as {
      data: { sources: { id: string }[]; targets: { label: string }[] };
    };
    expect(data.sources.map((source) => source.id)).toEqual(['p1']);
    // Bob is free and can take it; Chloé declared the day off, as the drop refuses.
    expect(data.targets.map((cible) => cible.label)).toEqual(['Bob']);
    dialog.closeAll();
  });

  it('says so on Enter when the line holds no shift, rather than doing nothing', async () => {
    await rendre(planningDeuxJours());
    const ligneBob = Array.from(racine().querySelectorAll<HTMLElement>('[data-ligne]')).find(
      (each) => each.getAttribute('aria-label')!.includes('Bob'),
    )!;

    ligneBob.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    expect(TestBed.inject(NotificationService).notify).toHaveBeenCalledWith(
      expect.objectContaining({ title: expect.stringContaining('Aucune vacation') }),
    );
  });

  it('exposes exactly one tab stop for the whole rail', async () => {
    await rendre(planningDeuxJours());

    // A grid where every line is tabbable is a grid nobody tabs past.
    expect(racine().querySelectorAll('.rail-cell[tabindex="0"]')).toHaveLength(1);
    expect(cellule(0).getAttribute('tabindex')).toBe('0');

    cellule(0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    expect(cellule(1).getAttribute('tabindex')).toBe('0');

    cellule(1).dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await fixture.whenStable();
    expect(cellule(2).getAttribute('tabindex')).toBe('0');
  });

  // The reset of the focus on a day change is a `linkedSignal` on the day
  // input: dropping it compiles, and nothing else asserted it.
  it('puts the tab stop back on the first line when the day changes', async () => {
    await rendre(planningDeuxJours());
    cellule(0).dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await fixture.whenStable();
    expect(cellule(2).getAttribute('tabindex')).toBe('0');

    await changerJour(2);

    expect(cellule(0).getAttribute('tabindex')).toBe('0');
    expect(racine().querySelectorAll('.rail-cell[tabindex="0"]')).toHaveLength(1);
  });

  it('keeps a tab stop when the filter drops the focused line', async () => {
    await rendre(planningDeuxJours());
    cellule(2).dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await fixture.whenStable();

    await basculerVue('Affectés');

    expect(racine().querySelectorAll('.rail-cell[tabindex="0"]')).toHaveLength(1);
  });

  it('stays proportional to the lines at the size of a real edition', async () => {
    // 150 animateurs, five shifts each, over a fifteen-hour day: what the rail
    // must not do is draw the scale again on every line. The hour gridlines are
    // a repeating background, so the DOM grows with the lines and the seats,
    // never with lines x hours.
    const animateurs = Array.from({ length: 150 }, (_, index) =>
      animateur(`A${String(index).padStart(3, '0')}`),
    );
    const postes = animateurs.flatMap((each, ligne) =>
      Array.from({ length: 5 }, (_, index) =>
        poste({
          id: `p${ligne}-${index}`,
          creneau: creneau({
            id: index + 1,
            heureDebut: `${String(9 + index * 3).padStart(2, '0')}:00`,
            heureFin: `${String(11 + index * 3).padStart(2, '0')}:00`,
          }),
          stand: stand('Tir'),
          animateur: each,
        }),
      ),
    );
    await rendre({ animateurs, postes, score: null });

    expect(racine().querySelectorAll('tbody tr')).toHaveLength(150);
    expect(racine().querySelectorAll('.rail-track')).toHaveLength(150);
    expect(racine().querySelectorAll('.rail-bloc')).toHaveLength(750);
    // The hour scale is drawn once, in the header, and never inside a line.
    expect(racine().querySelectorAll('tbody .rail-tick')).toHaveLength(0);
    // And it stays on screen: `position: sticky` needs a scrolling ancestor of
    // its own — measured at 153 lines, without it the scale simply left.
    expect(racine().querySelector('.rail-scroll .rail-table')).not.toBeNull();
    // 150 lines x 5 seats rendered in jsdom: past the 5 s default on a loaded
    // machine, and a timeout there says nothing about the rail.
  }, 20_000);

  it('drops the hour step on a hatched line, which would tile the pattern', async () => {
    await rendre(planningDeuxJours());

    const pistes = Array.from(racine().querySelectorAll('.rail-track')) as HTMLElement[];
    // Alice works, so her track carries the hour gridlines and their step.
    expect(pistes[0].style.backgroundSize).not.toBe('');
    // Chloé's is hatched: an hour-wide tile would restart the 45° pattern at
    // every hour instead of running continuously across the day.
    expect(pistes[2].style.backgroundSize).toBe('');
  });

  it('says what to do rather than showing an empty rail when nothing is solved', async () => {
    await rendre({ animateurs: [ALICE], postes: [], score: null });

    expect(racine().querySelector('.rail-table')).toBeNull();
    expect(racine().textContent!).toContain('Lancez une résolution depuis la page Solveur');
  });

  describe('pauses', () => {
    const rapport: RapportPauses = {
      journeesAnalysees: 1,
      pausesDues: 1,
      coupuresRepasDues: 0,
      coupuresRepasManquantes: 0,
      relaisManquants: 1,
      message: '',
      journees: [
        {
          animateurId: ALICE.id,
          nomComplet: 'Alice Martin',
          mineur: false,
          date: '2026-08-01',
          jour: 1,
          sequences: [
            {
              debut: '09:00:00',
              fin: '12:00:00',
              minutes: 180,
              pausesDues: [
                {
                  debut: '11:00:00',
                  fin: '11:20:00',
                  heureLimite: '15:00:00',
                  dureeMinutes: 20,
                  standId: 'tir',
                  standNom: 'Tir',
                  relais: [],
                  relaisDisponible: false,
                  simultanee: false,
                },
              ],
            },
          ],
          pausesPlanifiees: [],
          coupuresRepas: [],
        },
      ],
    };

    it('draws each break on its line, the relay-less one in the alert style, and names it in the summary', async () => {
      await rendre(planningDeuxJours(), {}, rapport);

      const segment = racine().querySelector('.rail-pause');
      expect(segment).not.toBeNull();
      expect(segment!.classList.contains('rail-pause-alerte')).toBe(true);
      expect(segment!.getAttribute('title')).toContain("personne d'autre sur le stand");
      expect(cellule(0).getAttribute('aria-label')).toContain('Pause 11:00 – 11:20 sur Tir');
      expect(racine().querySelector('.rail-swatch-pause-alerte')).not.toBeNull();
    });

    it('still draws the rail when the page could not read the breaks', async () => {
      await rendre(planningDeuxJours(), {}, null);

      expect(noms()).toHaveLength(3);
      expect(racine().querySelector('.rail-pause')).toBeNull();
    });
  });
});
