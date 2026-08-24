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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { Animateur, Creneau, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { CalendarDayPage } from './calendar-day-page';

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
    horaires: []
  };
}

function animateur(id: string, prenom: string, competences: Record<string, 'DEBUTANT'> = { ambiance: 'DEBUTANT' }) {
  return {
    id,
    prenom,
    nom: 'X',
    dateNaissance: '2000-01-01',
    manager: false,
    competences,
    souhaits: [],
    joursIndisponibles: []
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
  overrides: Partial<PosteAffectation> = {}
): PosteAffectation {
  return { id, stand: sur, creneau: sur2, animateur: occupant, ...overrides };
}

function planning(postes: PosteAffectation[]): PlanningEvenement {
  return { animateurs: [], postes, score: null };
}

interface Options {
  planning?: PlanningEvenement | null;
  loadRejects?: Error;
  countRejects?: boolean;
  verrous?: Partial<{
    estJourVerrouille: (date: string | null) => boolean;
    estStandVerrouille: (id: string) => boolean;
    estCreneauVerrouille: (id: number) => boolean;
  }>;
}

function mount(options: Options = {}) {
  const open = vi.fn();
  const loadForDisplay = options.loadRejects
    ? vi.fn(async () => {
        throw options.loadRejects;
      })
    : vi.fn(async () => options.planning ?? planning([]));
  const get = vi.fn(async () => {
    if (options.countRejects) {
      throw new Error('hors service');
    }
    return { assignments: 42 };
  });
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { get } },
      { provide: PlanningStateService, useValue: { loadForDisplay } },
      {
        provide: VerrouillageStore,
        useValue: {
          reload: vi.fn(async () => undefined),
          estJourVerrouille: options.verrous?.estJourVerrouille ?? (() => false),
          estStandVerrouille: options.verrous?.estStandVerrouille ?? (() => false),
          estCreneauVerrouille: options.verrous?.estCreneauVerrouille ?? (() => false)
        }
      },
      { provide: MatDialog, useValue: { open } }
    ]
  });
  return { fixture: TestBed.createComponent(CalendarDayPage), open, loadForDisplay };
}

function root(fixture: ComponentFixture<CalendarDayPage>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function texte(fixture: ComponentFixture<CalendarDayPage>): string {
  return root(fixture).textContent!.replace(/\s+/g, ' ').trim();
}

/** The stand lines currently on screen, with the markers that make them stand out. */
function lignes(fixture: ComponentFixture<CalendarDayPage>) {
  return Array.from(root(fixture).querySelectorAll('.day-stand')).map((ligne) => ({
    texte: ligne.textContent!.replace(/\s+/g, ' ').trim(),
    sousEffectif: ligne.classList.contains('understaffed-slot'),
    vide: ligne.classList.contains('empty-slot'),
    ecartAppreciation: ligne.classList.contains('appreciation-mismatch-slot'),
    icones: Array.from(ligne.querySelectorAll('mat-icon')).map((icone) => icone.textContent!.trim())
  }));
}

const AMBIANCE = stand('Loup-Garou');
const C1 = creneau({ id: 1 });

describe('CalendarDayPage rendering', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('invites the user to solve when there is nothing to show', async () => {
    const { fixture } = mount({ planning: planning([]) });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.empty-hint')).not.toBeNull();
    expect(texte(fixture)).toContain('Aucune donnée de planning disponible');
    expect(root(fixture).querySelectorAll('.day-card')).toHaveLength(0);
  });

  it('renders one card per event day, titled and dated', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, creneau({ id: 1, jour: 1, date: '2026-07-14' }), animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, creneau({ id: 2, jour: 2, date: '2026-07-15' }), animateur('a1', 'Camille'))
      ])
    });
    await fixture.whenStable();

    const cartes = Array.from(root(fixture).querySelectorAll('.day-card h2'));
    expect(cartes.map((each) => each.textContent!.replace(/\s+/g, ' ').trim())).toEqual([
      'Jour 1 — 2026-07-14',
      'Jour 2 — 2026-07-15'
    ]);
  });

  it('lists the animateurs of a line, comma-separated, each one clickable', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, animateur('a2', 'Alex'))
      ])
    });
    await fixture.whenStable();

    const boutons = Array.from(root(fixture).querySelectorAll('.affectation-link'));
    expect(boutons.map((each) => each.textContent!.trim())).toEqual(['Camille X', 'Alex X']);
    expect(lignes(fixture)[0].texte).toContain('Camille X, Alex X');
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

  it('flags an understaffed line with a warning icon naming the shortfall', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille')),
        poste('p2', AMBIANCE, C1, null)
      ])
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
    const { fixture } = mount({ planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]) });
    await fixture.whenStable();

    expect(lignes(fixture)[0].sousEffectif).toBe(false);
    expect(lignes(fixture)[0].icones).not.toContain('warning');
  });

  it('marks a meal-pause line as an indication, and its filled seat as full coverage', async () => {
    // EFFECTIF_REDUIT: the découpage generates a *single* seat on the pause
    // vacation, so filling it is full coverage, not a shortfall. The halving
    // happens upstream, at seat generation — the flag only drives the icon.
    const pause = creneau({ id: 9, couverturePause: true });
    const { fixture } = mount({ planning: planning([poste('p1', AMBIANCE, pause, animateur('a1', 'Camille'))]) });
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
        poste('p2', AMBIANCE, pause, null)
      ])
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
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille', { autre: 'DEBUTANT' }))])
    });
    await fixture.whenStable();

    const ligne = lignes(fixture)[0];
    expect(ligne.ecartAppreciation).toBe(true);
    expect(ligne.icones).toContain('psychology');
    expect(root(fixture).querySelector('.day-card')!.classList).toContain('has-appreciation-mismatch');
  });

  it('says when a line only covers part of its créneau', async () => {
    const { fixture } = mount({
      planning: planning([
        poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'), {
          heureDebutEffective: '10:00',
          heureFinEffective: '11:00'
        })
      ])
    });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.day-stand-partial')!.textContent!.replace(/\s+/g, ' ')).toContain(
      'ouvert 10:00 – 11:00 seulement'
    );
  });

  it('shows no partial-closure note when the line covers the whole créneau', async () => {
    const { fixture } = mount({ planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]) });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.day-stand-partial')).toBeNull();
  });

  it('padlocks a day frozen by a lock', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
      verrous: { estJourVerrouille: (date) => date === '2026-07-14' }
    });
    await fixture.whenStable();

    const cadenas = root(fixture).querySelector('.day-card h2 .verrouille-icon')!;
    expect(cadenas.textContent!.trim()).toBe('lock');
    expect(cadenas.getAttribute('aria-label')).toContain('Verrouillé');
  });

  it('padlocks a single line frozen by its stand', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', AMBIANCE, C1, animateur('a1', 'Camille'))]),
      verrous: { estStandVerrouille: (id) => id === 'Loup-Garou' }
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
        poste('p3', AMBIANCE, C1, null)
      ])
    });
    await fixture.whenStable();
    expect(lignes(fixture)).toHaveLength(2);

    filtrerProblemes(fixture, true);
    await fixture.whenStable();

    const restantes = lignes(fixture);
    expect(restantes).toHaveLength(1);
    expect(restantes[0].texte).toContain('Loup-Garou');
    expect(restantes[0].texte).not.toContain('Dixit');
  });

  it('drops a whole day from the grid when the filter leaves nothing on it', async () => {
    const { fixture } = mount({
      planning: planning([poste('p1', stand('Dixit'), C1, animateur('a1', 'Camille'))])
    });
    await fixture.whenStable();
    expect(root(fixture).querySelectorAll('.day-card')).toHaveLength(1);

    filtrerProblemes(fixture, true);
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.day-card')).toHaveLength(0);
    expect(texte(fixture)).toContain('Aucune donnée de planning disponible');
  });

  function filtrerProblemes(fixture: ComponentFixture<CalendarDayPage>, actif: boolean): void {
    (fixture.componentInstance as unknown as { seulementProblemes: { set(value: boolean): void } }).seulementProblemes.set(
      actif
    );
  }

  it('opens the explanation dialog on the seat that was clicked', async () => {
    const camille = animateur('a1', 'Camille');
    const planningAffiche = planning([poste('p1', AMBIANCE, C1, camille)]);
    const { fixture, open } = mount({ planning: planningAffiche });
    await fixture.whenStable();

    (root(fixture).querySelector('.affectation-link') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(open).toHaveBeenCalledOnce();
    const config = open.mock.calls[0][1] as { data: { poste: PosteAffectation; planning: PlanningEvenement } };
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

  it('shows the failure instead of an empty calendar when the planning cannot be loaded', async () => {
    const { fixture } = mount({ loadRejects: new Error('planning illisible') });
    await fixture.whenStable();

    const erreur = root(fixture).querySelector('.calendar-empty')!;
    expect(erreur.textContent).toContain('planning illisible');
    // The "run a solve" hint would be misleading here: the data exists, it failed.
    expect(root(fixture).querySelector('.empty-hint')).toBeNull();
  });

  it('reloads the planning on the refresh button', async () => {
    const { fixture, loadForDisplay } = mount({ planning: planning([]) });
    await fixture.whenStable();
    expect(loadForDisplay).toHaveBeenCalledOnce();

    const rafraichir = Array.from(root(fixture).querySelectorAll('button')).find((each) =>
      each.textContent?.includes('Actualiser')
    )!;
    rafraichir.click();
    await fixture.whenStable();

    expect(loadForDisplay).toHaveBeenCalledTimes(2);
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
      'lock'
    ]);
  });
});
