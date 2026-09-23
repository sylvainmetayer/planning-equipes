// A rendering test for the day calendar, whose template was at 0 %: the page is
// read every day to spot the two wrong lines among the dozens right ones, and
// everything that makes them stand out — the warning icons, their tooltips, the
// padlocks, the "problem lines only" filter — lives in the template.
//
// `calendar-day-page.spec.ts` next door covers `buildDays`, the grouping. What
// no logic test can see is which of those groups reaches the screen, and with
// which markers on it.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ApiService } from '../../core/api.service';
import { provideRouter } from '@angular/router';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { Animateur, Creneau, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { CalendarDayView } from './calendar-day-vue';

function stand(id: string, typologiesProposees: string[] = ['ambiance']): Stand {
  return {
    id,
    nom: id,
    typologiesProposees,
    effectifMin: 1,
    effectifMax: 4,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function animateur(
  id: string,
  prenom: string,
  competences: Record<string, 'DEBUTANT'> = { ambiance: 'DEBUTANT' },
) {
  return {
    id,
    prenom,
    nom: 'X',
    dateNaissance: '2000-01-01',
    manager: false,
    competences,
    souhaits: [],
    joursIndisponibles: [],
  } satisfies Animateur;
}

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-07-14', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function poste(
  id: string,
  sur: Stand | null,
  sur2: Creneau | null,
  occupant: Animateur | null,
  overrides: Partial<PosteAffectation> = {},
): PosteAffectation {
  return { id, stand: sur, creneau: sur2, animateur: occupant, ...overrides };
}

function planning(postes: PosteAffectation[]): PlanningEvenement {
  return { animateurs: [], postes, score: null };
}

interface Options {
  planning?: PlanningEvenement | null;
  jour?: number;
  stand?: string;
  animateur?: string;
  countRejects?: boolean;
  verrous?: Partial<{
    estJourVerrouille: (date: string | null) => boolean;
    estStandVerrouille: (id: string) => boolean;
    estCreneauVerrouille: (id: number) => boolean;
  }>;
}

function mount(options: Options = {}) {
  // MatDialog.open always hands back a ref; the page reads `afterClosed()` on
  // it to reload when the repair assistant wrote to the plan (issue #71), so
  // the double has to honour that contract too.
  const open = vi.fn((...args: unknown[]) => {
    void args;
    return { afterClosed: () => of<unknown>(undefined) };
  });
  const get = vi.fn(async () => {
    if (options.countRejects) {
      throw new Error('hors service');
    }
    return { assignments: 42 };
  });
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ApiService, useValue: { get } },
      { provide: SolverJobService, useValue: { editingLocked: () => false } },
      { provide: NotificationService, useValue: { notify: vi.fn() } },
      { provide: AffectationExplanationService, useValue: { deplacer: vi.fn() } },

      {
        provide: VerrouillageStore,
        useValue: {
          reload: vi.fn(async () => undefined),
          estJourVerrouille: options.verrous?.estJourVerrouille ?? (() => false),
          estStandVerrouille: options.verrous?.estStandVerrouille ?? (() => false),
          estCreneauVerrouille: options.verrous?.estCreneauVerrouille ?? (() => false),
        },
      },
      { provide: MatDialog, useValue: { open } },
    ],
  });
  const fixture = TestBed.createComponent(CalendarDayView);
  fixture.componentRef.setInput('planning', options.planning ?? planning([]));
  for (const key of ['jour', 'stand', 'animateur'] as const) {
    if (options[key] !== undefined) {
      fixture.componentRef.setInput(key, options[key]);
    }
  }
  return { fixture, open };
}

function root(fixture: ComponentFixture<CalendarDayView>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function text(fixture: ComponentFixture<CalendarDayView>): string {
  return root(fixture).textContent!.replace(/\s+/g, ' ').trim();
}

/** The stand lines currently on screen, with the markers that make them stand out. */
function lignes(fixture: ComponentFixture<CalendarDayView>) {
  return Array.from(root(fixture).querySelectorAll('.day-stand')).map((ligne) => ({
    texte: ligne.textContent!.replace(/\s+/g, ' ').trim(),
    sousEffectif: ligne.classList.contains('understaffed-slot'),
    vide: ligne.classList.contains('empty-slot'),
    ecartAppreciation: ligne.classList.contains('appreciation-mismatch-slot'),
    icones: Array.from(ligne.querySelectorAll('mat-icon')).map((icone) =>
      icone.textContent!.trim(),
    ),
  }));
}

const AMBIANCE = stand('Loup-Garou');
const C1 = creneau({ id: 1 });

describe('CalendarDayView rendering', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('invites the user to solve when there is nothing to show', async () => {
    const { fixture } = mount({ planning: planning([]) });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.empty-hint')).not.toBeNull();
    expect(text(fixture)).toContain('Aucune donnée de planning disponible');
    expect(root(fixture).querySelectorAll('.day-card')).toHaveLength(0);
  });

  it('renders the one day the page hands it, titled and dated, the first by default', async () => {
    const deuxJours = planning([
      poste(
        'p1',
        AMBIANCE,
        creneau({ id: 1, jour: 1, date: '2026-07-14' }),
        animateur('a1', 'Camille'),
      ),
      poste(
        'p2',
        AMBIANCE,
        creneau({ id: 2, jour: 2, date: '2026-07-15' }),
        animateur('a1', 'Camille'),
      ),
    ]);
    const { fixture } = mount({ planning: deuxJours });
    await fixture.whenStable();

    const titres = () =>
      Array.from(root(fixture).querySelectorAll('.day-card h2')).map((each) =>
        each.textContent!.replace(/\s+/g, ' ').trim(),
      );
    expect(titres()).toEqual(['Jour 1 — 2026-07-14']);

    fixture.componentRef.setInput('jour', 2);
    await fixture.whenStable();
    expect(titres()).toEqual(['Jour 2 — 2026-07-15']);
  });

  it('keeps only the lines the shared filters name: a stand, a person', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', stand('Dixit'), C1, animateur('a2', 'Alex')),
      ]),
      stand: 'Dixit',
    });
    await fixture.whenStable();
    expect(
      lignes(fixture)
        .map((ligne) => ligne.texte)
        .join(' '),
    ).not.toContain('Loup-Garou');

    fixture.componentRef.setInput('stand', '');
    fixture.componentRef.setInput('animateur', 'a1');
    await fixture.whenStable();
    expect(
      lignes(fixture)
        .map((ligne) => ligne.texte)
        .join(' '),
    ).toContain('Loup-Garou');
    expect(
      lignes(fixture)
        .map((ligne) => ligne.texte)
        .join(' '),
    ).not.toContain('Dixit');
  });

  it('lists the animateurs of a line, comma-separated, each one clickable', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, animateur('a2', 'Alex')),
      ]),
    });
    await fixture.whenStable();

    const boutons = Array.from(root(fixture).querySelectorAll('.affectation-link'));
    expect(boutons.map((each) => each.textContent!.trim())).toEqual(['Camille X', 'Alex X']);
    // The ⠿ handle sits between the names: the drag surface is not the name
    // itself, so the row's raw text carries it (issue #308, review).
    expect(lignes(fixture)[0].texte.replaceAll('⠿', '')).toContain('Camille X, Alex X');
  });

  // The drag-and-drop is announced as under test on the screen that carries
  // it: the warning has to be there every time it is opened, so it is asserted
  // like any other part of the page.
  it('says on the page that dragging is still under test', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    const bandeau = root(fixture).querySelector('.essai-bandeau');
    expect(bandeau?.textContent).toContain('en cours de test');
  });

  it('marks an unfilled line as such rather than leaving it blank', async () => {
    const { fixture } = mount({ planning: planning([poste('p1', AMBIANCE, C1, null)]) });
    await fixture.whenStable();

    const ligne = lignes(fixture)[0];
    expect(ligne.vide).toBe(true);
    expect(ligne.texte).toContain('(non assigné)');
    // Nobody is on it, so there is nothing to explain.
    expect(root(fixture).querySelectorAll('.affectation-link')).toHaveLength(0);
  });

  // Issue #308: every free seat is a drop target of its own, named by its poste id.
  it('draws each free seat as a chip carrying its poste id, next to the names', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, null),
        poste('p3', AMBIANCE, C1, null),
      ]),
    });
    await fixture.whenStable();

    const libres = Array.from(root(fixture).querySelectorAll<HTMLElement>('.siege-libre'));
    expect(libres.map((chip) => chip.dataset['posteId'])).toEqual(['p2', 'p3']);
    // Each free seat is also the question « who could take it? », asked of the
    // bench on that créneau and that stand (issue #489).
    expect(libres.map((chip) => chip.getAttribute('href'))).toEqual([
      '/diagnostic?onglet=banc&creneau=1&stand=Loup-Garou',
      '/diagnostic?onglet=banc&creneau=1&stand=Loup-Garou',
    ]);
    // The seat id sits on the draggable wrapper, which contains the name: a
    // drop resolves it with closest(), from wherever the pointer landed.
    expect(
      root(fixture).querySelector<HTMLElement>('.affectation-glissable')!.dataset['posteId'],
    ).toBe('p1');
  });

  it('flags an understaffed line with a warning icon naming the shortfall', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, null),
      ]),
    });
    await fixture.whenStable();

    const ligne = lignes(fixture)[0];
    expect(ligne.sousEffectif).toBe(true);
    expect(ligne.icones).toContain('warning');
    const icone = root(fixture).querySelector('.day-stand .understaffed-icon')!;
    // One of two seats filled: the label has to carry both numbers.
    expect(icone.getAttribute('aria-label')).toContain('1');
    expect(icone.getAttribute('aria-label')).toContain('2');
  });

  it('does not flag a fully staffed line', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    expect(lignes(fixture)[0].sousEffectif).toBe(false);
    expect(lignes(fixture)[0].icones).not.toContain('warning');
  });

  it('marks a meal-pause line as an indication, and its filled seat as full coverage', async () => {
    // EFFECTIF_REDUIT: the découpage generates a *single* seat on the pause
    // vacation, so filling it is full coverage, not a shortfall. The halving
    // happens upstream, at seat generation — the flag only drives the icon.
    const pause = creneau({ id: 9, couverturePause: true });
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, pause, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    const ligne = lignes(fixture)[0];
    expect(ligne.icones).toContain('restaurant');
    expect(ligne.sousEffectif).toBe(false);
    expect(ligne.icones).not.toContain('warning');
  });

  it('still flags a meal-pause line short of the seats it did generate', async () => {
    const pause = creneau({ id: 9, couverturePause: true });
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, pause, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, pause, null),
      ]),
    });
    await fixture.whenStable();

    // Two seats generated for the pause, one filled: the reduced headcount was
    // already taken into account when generating them, so this is a real gap.
    const ligne = lignes(fixture)[0];
    expect(ligne.icones).toContain('restaurant');
    expect(ligne.sousEffectif).toBe(true);
    expect(ligne.icones).toContain('warning');
  });

  it('flags a seat held by someone with no appreciation for the stand', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille', { autre: 'DEBUTANT' })),
      ]),
    });
    await fixture.whenStable();

    const ligne = lignes(fixture)[0];
    expect(ligne.ecartAppreciation).toBe(true);
    expect(ligne.icones).toContain('psychology');
    expect(root(fixture).querySelector('.day-card')!.classList).toContain(
      'has-appreciation-mismatch',
    );
  });

  it('says when a line only covers part of its créneau', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'), {
          heureDebutEffective: '10:00',
          heureFinEffective: '11:00',
        }),
      ]),
    });
    await fixture.whenStable();

    expect(
      root(fixture).querySelector('.day-stand-partial')!.textContent!.replace(/\s+/g, ' '),
    ).toContain('ouvert 10:00 – 11:00 seulement');
  });

  it('shows no partial-closure note when the line covers the whole créneau', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.day-stand-partial')).toBeNull();
  });

  it('padlocks a day frozen by a lock', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
      verrous: { estJourVerrouille: (date) => date === '2026-07-14' },
    });
    await fixture.whenStable();

    const cadenas = root(fixture).querySelector('.day-card h2 .verrouille-icon')!;
    expect(cadenas.textContent!.trim()).toBe('lock');
    expect(cadenas.getAttribute('aria-label')).toContain('Verrouillé');
  });

  it('padlocks a single line frozen by its stand', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
      verrous: { estStandVerrouille: (id) => id === 'Loup-Garou' },
    });
    await fixture.whenStable();

    expect(lignes(fixture)[0].icones).toContain('lock');
    expect(root(fixture).querySelector('.day-card h2 .verrouille-icon')).toBeNull();
  });

  it('narrows the day to the lines needing attention when the filter is ticked', async () => {
    const { fixture } = mount({
      planning: planning([
        // Fine: fully staffed and appreciated.
        poste('p1', stand('Dixit'), C1, animateur('a1', 'Camille')),
        // Understaffed.
        poste('p2', AMBIANCE, C1, animateur('a2', 'Alex')),
        poste('p3', AMBIANCE, C1, null),
      ]),
    });
    await fixture.whenStable();
    expect(lignes(fixture)).toHaveLength(2);

    filterProblemes(fixture, true);
    await fixture.whenStable();

    const restantes = lignes(fixture);
    expect(restantes).toHaveLength(1);
    expect(restantes[0].texte).toContain('Loup-Garou');
    expect(restantes[0].texte).not.toContain('Dixit');
  });

  it('drops a whole day from the grid when the filter leaves nothing on it', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', stand('Dixit'), C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();
    expect(root(fixture).querySelectorAll('.day-card')).toHaveLength(1);

    filterProblemes(fixture, true);
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.day-card')).toHaveLength(0);
    // The plan is not empty, the filters are: the wording says so.
    expect(text(fixture)).toContain('Aucune ligne ne correspond aux filtres');
  });

  function filterProblemes(fixture: ComponentFixture<CalendarDayView>, actif: boolean): void {
    (
      fixture.componentInstance as unknown as { seulementProblemes: { set(value: boolean): void } }
    ).seulementProblemes.set(actif);
  }

  it('opens the explanation dialog on the seat that was clicked', async () => {
    const camille = animateur('a1', 'Camille');
    const planningAffiche = planning([poste('p1', AMBIANCE, C1, camille)]);
    const { fixture, open } = mount({ planning: planningAffiche });
    await fixture.whenStable();

    (root(fixture).querySelector('.affectation-link') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(open).toHaveBeenCalledOnce();
    const config = open.mock.calls[0][1] as {
      data: { poste: PosteAffectation; planning: PlanningEvenement };
    };
    expect(config.data.poste.id).toBe('p1');
    expect(config.data.planning).toBe(planningAffiche);
  });

  it('reports how many assignments are persisted', async () => {
    const { fixture } = mount({ planning: planning([]) });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.calendar-meta')!.textContent).toContain('42');
  });

  it('says the persisted count is unavailable rather than showing a stale one', async () => {
    const { fixture } = mount({ planning: planning([]), countRejects: true });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.calendar-meta')!.textContent).toContain('n/d');
  });

  it('asks the page to re-read the plan once the repair assistant wrote to it', async () => {
    const camille = animateur('a1', 'Camille');
    const { fixture, open } = mount({ planning: planning([poste('p1', AMBIANCE, C1, camille)]) });
    open.mockReturnValue({ afterClosed: () => of({ applique: true }) });
    await fixture.whenStable();
    const rechargements = vi.fn();
    fixture.componentInstance.rechargement.subscribe(rechargements);

    (root(fixture).querySelector('.affectation-link') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(rechargements).toHaveBeenCalledOnce();
  });

  it('explains its four markers in a legend', async () => {
    const { fixture } = mount({ planning: planning([]) });
    await fixture.whenStable();

    const legende = Array.from(root(fixture).querySelectorAll('.calendar-legende li'));
    expect(legende).toHaveLength(4);
    expect(legende.map((each) => each.querySelector('mat-icon')!.textContent!.trim())).toEqual([
      'warning',
      'restaurant',
      'psychology',
      'lock',
    ]);
  });

  // RGAA 7.3 / WCAG 2.5.7: the drag has a twin a keyboard and a single click
  // reach — the handle is a named button opening the move as a dialog, and the
  // answer goes through the same call as a drop.
  describe('the keyboard twin of the drag', () => {
    const DEUX = stand('Dixit');
    const alice = animateur('a1', 'Alice');
    const bob = animateur('b1', 'Bob');
    const plan = planning([
      poste('P1', AMBIANCE, C1, alice),
      poste('P2', DEUX, C1, bob),
      poste('P3', DEUX, C1, null),
    ]);

    it('names the handle and opens the move with every other seat of the day', async () => {
      const { fixture, open } = mount({ planning: plan });
      await fixture.whenStable();

      const poignee = root(fixture).querySelector<HTMLButtonElement>(
        'button.affectation-poignee[aria-label*="Alice"]',
      )!;
      expect(poignee).not.toBeNull();
      poignee.click();

      expect(open).toHaveBeenCalledTimes(1);
      const data = (open.mock.calls[0] as unknown[])[1] as {
        data: { targets: { id: string; label: string }[] };
      };
      const cibles = data.data.targets;
      expect(cibles.map((cible) => cible.id).sort()).toEqual(['P2', 'P3']);
      expect(cibles.find((cible) => cible.id === 'P3')!.label).toContain('siège libre');
      expect(cibles.find((cible) => cible.id === 'P2')!.label).toContain('Bob');
    });

    it('moves through the same call as a drop', async () => {
      const { fixture, open } = mount({ planning: plan });
      open.mockReturnValueOnce({ afterClosed: () => of<unknown>({ source: null, target: 'P3' }) });
      const move = TestBed.inject(AffectationExplanationService).deplacer as ReturnType<
        typeof vi.fn
      >;
      move.mockResolvedValue({
        animateurSourceId: 'a1',
        animateurCibleId: null,
        posteCibleId: 'P3',
        scoreAvant: { hardScore: 0, mediumScore: 0, softScore: 0 },
        scoreApres: { hardScore: 0, mediumScore: 0, softScore: 0 },
      });
      await fixture.whenStable();

      root(fixture)
        .querySelector<HTMLButtonElement>('button.affectation-poignee[aria-label*="Alice"]')!
        .click();

      await vi.waitFor(() => expect(move).toHaveBeenCalledWith('P1', { posteId: 'P3' }, 'a1'));
    });
  });
});
