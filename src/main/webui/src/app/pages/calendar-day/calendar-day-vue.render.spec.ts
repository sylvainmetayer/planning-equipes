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
import { APP_CONFIG } from '../../core/app-config';
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

function animateur(id: string, prenom: string, skills?: Record<string, 'DEBUTANT'>) {
  return {
    id,
    prenom,
    nom: 'X',
    dateNaissance: '2000-01-01',
    manager: false,
    competences: skills ?? { ambiance: 'DEBUTANT' },
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
  dragDropEnabled?: boolean;
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
  const open = vi.fn((..._args: unknown[]) => {
    return { afterClosed: () => of<unknown>(undefined) };
  });
  const get = vi.fn(async () => ({}));
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
      { provide: APP_CONFIG, useValue: { dragDropEnabled: options.dragDropEnabled ?? false } },
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

/** The stands the table has a row for, in order. */
function rangees(fixture: ComponentFixture<CalendarDayView>): string[] {
  return Array.from(root(fixture).querySelectorAll('tbody th')).map((each) =>
    each.querySelector('a')!.textContent!.trim(),
  );
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

function filterProblems(fixture: ComponentFixture<CalendarDayView>, active: boolean): void {
  (
    fixture.componentInstance as unknown as { seulementProblemes: { set(value: boolean): void } }
  ).seulementProblemes.set(active);
}

describe('CalendarDayView rendering', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('invites the user to solve when there is nothing to show', async () => {
    const { fixture } = mount({ planning: planning([]) });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.empty-hint')).not.toBeNull();
    expect(text(fixture)).toContain('Aucune donnée de planning disponible');
    expect(root(fixture).querySelectorAll('.jour-table')).toHaveLength(0);
  });

  it('renders the one day the page hands it as a table, its timeslots without seconds', async () => {
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
        creneau({
          id: 2,
          jour: 2,
          date: '2026-07-15',
          heureDebut: '14:00:00',
          heureFin: '16:00:00',
        }),
        animateur('a1', 'Camille'),
      ),
    ]);
    const { fixture } = mount({ planning: deuxJours });
    await fixture.whenStable();

    const entetes = () =>
      Array.from(root(fixture).querySelectorAll('thead th')).map((each) =>
        each.textContent!.replace(/\s+/g, ' ').trim(),
      );
    expect(entetes()).toEqual(['Stand', '10:00–12:00']);
    expect(rangees(fixture)).toEqual(['Loup-Garou']);

    fixture.componentRef.setInput('jour', 2);
    await fixture.whenStable();
    expect(entetes()).toEqual(['Stand', '14:00–16:00']);
  });

  // #712: a stand shut for a timeslot is its schedule, not a gap — a neutral
  // « fermé », where an empty seat is a red chip.
  it('says fermé where a stand does not open, in a neutral cell', async () => {
    const afternoon = creneau({ id: 2, heureDebut: '14:00', heureFin: '16:00' });
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', stand('Dixit'), afternoon, animateur('a2', 'Alex')),
      ]),
    });
    await fixture.whenStable();

    const fermees = Array.from(root(fixture).querySelectorAll('td.jour-table-ferme'));
    expect(fermees).toHaveLength(2);
    expect(fermees.map((cellule) => cellule.textContent!.trim())).toEqual(['fermé', 'fermé']);
    expect(rangees(fixture)).toEqual(['Dixit', 'Loup-Garou']);
  });

  it('links each stand to its fiche and names its location under it', async () => {
    const place = { ...AMBIANCE, emplacement: { id: 'E1', nom: 'Grande halle' } } as Stand;
    const { fixture } = mount({
      planning: planning([poste('p1', place, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    const entete = root(fixture).querySelector('tbody th')!;
    expect(entete.querySelector('a')!.getAttribute('href')).toBe('/stands/Loup-Garou');
    expect(entete.querySelector('.jour-table-emplacement')!.textContent).toContain('Grande halle');
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
    expect(rangees(fixture)).toEqual(['Dixit']);

    fixture.componentRef.setInput('stand', '');
    fixture.componentRef.setInput('animateur', 'a1');
    await fixture.whenStable();
    expect(rangees(fixture)).toEqual(['Loup-Garou']);

    fixture.componentRef.setInput('animateur', '');
    fixture.componentRef.setInput('standsRetenus', new Set(['Dixit']));
    await fixture.whenStable();
    expect(rangees(fixture)).toEqual(['Dixit']);
  });

  it('lists the animateurs of a line, one chip per name, each one clickable', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, animateur('a2', 'Alex')),
      ]),
    });
    await fixture.whenStable();

    const boutons = Array.from(root(fixture).querySelectorAll('.affectation-link'));
    expect(boutons.map((each) => each.textContent!.trim())).toEqual(['Camille X', 'Alex X']);
    // One name per chip, never a comma-separated run.
    expect(lignes(fixture)[0].texte).not.toContain(',');
  });

  // The drag-and-drop is announced as under test on the screen that carries
  // it: wherever the instance offers it, the warning is there every time the
  // page is opened, so it is asserted like any other part of the page.
  it('says on the page that dragging is still under test', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
      dragDropEnabled: true,
    });
    await fixture.whenStable();

    const bandeau = root(fixture).querySelector('.essai-bandeau');
    expect(bandeau?.textContent).toContain('en cours de test');
    const nom = root(fixture).querySelector<HTMLElement>('.affectation-glissable')!;
    expect(nom.querySelector('.affectation-poignee')).not.toBeNull();
    expect(nom.classList).toContain('cdk-drag');
    expect(nom.classList).not.toContain('cdk-drag-disabled');
  });

  // GLISSER_DEPOSER_ACTIF is off by default: no handle, no warning, no gesture —
  // the name still opens « Pourquoi lui ? ».
  it('offers no drag and drop when the instance has not switched it on', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.essai-bandeau')).toBeNull();
    const nom = root(fixture).querySelector<HTMLElement>('.affectation-glissable')!;
    expect(nom.querySelector('.affectation-poignee')).toBeNull();
    // Not a disabled drag: no drag at all, so nothing is registered for it.
    expect(nom.classList).not.toContain('cdk-drag');
    expect(nom.querySelector('.affectation-link')).not.toBeNull();
    expect(root(fixture).querySelector('.day-stand')!.classList).toContain(
      'cdk-drop-list-disabled',
    );
  });

  it('marks an unfilled line as such rather than leaving it blank', async () => {
    const { fixture } = mount({ planning: planning([poste('p1', AMBIANCE, C1, null)]) });
    await fixture.whenStable();

    const ligne = lignes(fixture)[0];
    expect(ligne.vide).toBe(true);
    expect(ligne.texte).toContain('siège libre');
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

    const libres = Array.from(
      root(fixture).querySelectorAll<HTMLElement>('.jour-table .siege-libre'),
    );
    expect(libres.map((chip) => chip.dataset['posteId'])).toEqual(['p2', 'p3']);
    // Each free seat is also the question « who could take it? », asked of
    // the Siège panel on that very seat: a button, not a link leaving the day.
    expect(libres.map((chip) => chip.tagName)).toEqual(['BUTTON', 'BUTTON']);
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

    // The ordinary case of a stand opening later than the timeslot: a plain
    // window, never the alarm it used to be on fifty lines.
    expect(root(fixture).querySelector('.day-stand-partial')!.textContent!.trim()).toBe(
      '10:00–11:00',
    );
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

    const cadenas = root(fixture).querySelector('.jour-table-verrou')!;
    expect(cadenas.textContent).toContain('lock');
    expect(cadenas.textContent).toContain('Journée verrouillée');
  });

  it('padlocks a single line frozen by its stand', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
      verrous: { estStandVerrouille: (id) => id === 'Loup-Garou' },
    });
    await fixture.whenStable();

    expect(lignes(fixture)[0].icones).toContain('lock');
    expect(root(fixture).querySelector('.jour-table-verrou')).toBeNull();
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

    filterProblems(fixture, true);
    await fixture.whenStable();

    expect(lignes(fixture)).toHaveLength(1);
    expect(rangees(fixture)).toEqual(['Loup-Garou']);
  });

  // #712: the relecture chips narrow the table to what they count.
  it('narrows the table to the seats a relecture chip counts: empty, then locked', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', stand('Dixit'), C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, null),
      ]),
      verrous: { estStandVerrouille: (id) => id === 'Dixit' },
    });
    fixture.componentRef.setInput('sieges', 'vides');
    await fixture.whenStable();
    expect(rangees(fixture)).toEqual(['Loup-Garou']);

    fixture.componentRef.setInput('sieges', 'verrous');
    await fixture.whenStable();
    expect(rangees(fixture)).toEqual(['Dixit']);
  });

  it('drops every row when the filter leaves nothing on the day', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', stand('Dixit'), C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();
    expect(rangees(fixture)).toHaveLength(1);

    filterProblems(fixture, true);
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.jour-table')).toHaveLength(0);
    // The plan is not empty, the filters are: the wording says so.
    expect(text(fixture)).toContain('Aucune ligne ne correspond aux filtres');
  });

  // #711: a name opens the page's Siège panel on its seat — the view emits,
  // the page opens; no dialog in front of the day any more.
  it('asks the page to open the Siège panel on the seat that was clicked', async () => {
    const camille = animateur('a1', 'Camille');
    const { fixture, open } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, camille), poste('p2', AMBIANCE, C1, null)]),
    });
    await fixture.whenStable();
    const opened: string[] = [];
    fixture.componentInstance.seatSelected.subscribe((id) => opened.push(id));

    (root(fixture).querySelector('.affectation-link') as HTMLButtonElement).click();
    (root(fixture).querySelector('.siege-libre') as HTMLButtonElement).click();

    expect(opened).toEqual(['p1', 'p2']);
    expect(open).not.toHaveBeenCalled();
  });

  it('marks the seat the panel is open on', async () => {
    const camille = animateur('a1', 'Camille');
    const { fixture } = mount({ planning: planning([poste('p1', AMBIANCE, C1, camille)]) });
    fixture.componentRef.setInput('openSeatId', 'p1');
    await fixture.whenStable();

    const nom = root(fixture).querySelector('.affectation-link')!;
    expect(nom.classList).toContain('siege-ouvert');
    expect(nom.getAttribute('aria-pressed')).toBe('true');
  });

  // #712: « Affectations enregistrées en base » was a technical count, not an answer.
  it('prints no technical count of persisted assignments', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    expect(text(fixture)).not.toContain('enregistrées en base');
  });

  it('explains the free seat and its four markers in a legend', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
    });
    await fixture.whenStable();

    const legende = Array.from(root(fixture).querySelectorAll('.calendar-legende li'));
    expect(legende).toHaveLength(5);
    expect(legende[0].textContent).toContain('siège libre');
    expect(
      legende.slice(1).map((each) => each.querySelector('mat-icon')!.textContent!.trim()),
    ).toEqual(['warning', 'restaurant', 'psychology', 'lock']);
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
      const { fixture, open } = mount({ planning: plan, dragDropEnabled: true });
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
      expect(cibles.map((target) => target.id).sort((a, b) => a.localeCompare(b))).toEqual([
        'P2',
        'P3',
      ]);
      expect(cibles.find((cible) => cible.id === 'P3')!.label).toContain('siège libre');
      expect(cibles.find((cible) => cible.id === 'P2')!.label).toContain('Bob');
    });

    it('leaves out the lines a lock closes, as the drop does', async () => {
      const { fixture, open } = mount({
        planning: plan,
        dragDropEnabled: true,
        verrous: { estStandVerrouille: (id) => id === 'Dixit' },
      });
      await fixture.whenStable();

      root(fixture)
        .querySelector<HTMLButtonElement>('button.affectation-poignee[aria-label*="Alice"]')!
        .click();

      const data = (open.mock.calls[0] as unknown[])[1] as { data: { targets: unknown[] } };
      expect(data.data.targets).toEqual([]);
    });

    it('moves through the same call as a drop', async () => {
      const { fixture, open } = mount({ planning: plan, dragDropEnabled: true });
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

    // The flag governs the pointer gesture only: no handle without it, and
    // the move is reached from the Siège panel instead (« Déplacer vers »).
    it('offers no drag handle when the instance has not switched drag and drop on', async () => {
      const { fixture } = mount({ planning: plan });
      await fixture.whenStable();

      expect(root(fixture).querySelector('button.affectation-poignee')).toBeNull();
    });
  });
});
