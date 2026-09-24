// What `repos.spec.ts` cannot see: which lines reach the screen, that the two
// filters and the URL agree, and that the grid can be walked without a mouse.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { PlanningStateService } from '../../core/planning-state.service';
import { Animateur, Creneau, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { ReposPage } from './repos-page';

function stand(id: string): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
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

/** Alice works both days, Bob only the second, Chloé declared day 1 unavailable and works day 2. */
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
      poste({
        id: 'p4',
        creneau: creneau({ id: 4, jour: 2, date: '2026-08-02' }),
        stand: stand('Loup'),
        animateur: CHLOE,
      }),
    ],
    score: null,
  };
}

describe('ReposPage', () => {
  let fixture: ComponentFixture<ReposPage>;

  async function rendre(
    evenement: PlanningEvenement,
    queryParams: Record<string, string> = {},
  ): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
        {
          provide: PlanningStateService,
          useValue: { loadForDisplay: vi.fn(async () => evenement) },
        },
      ],
    });
    fixture = TestBed.createComponent(ReposPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function noms(): string[] {
    return Array.from(racine().querySelectorAll('.repos-row-label')).map((each) =>
      each.textContent!.trim(),
    );
  }

  function cellule(ligne: number, colonne: number): HTMLElement {
    return racine().querySelector(
      `[data-ligne="${ligne}"][data-colonne="${colonne}"]`,
    ) as HTMLElement;
  }

  async function saisirFiltre(valeur: string): Promise<void> {
    const champ = racine().querySelector('input[data-page-filter]') as HTMLInputElement;
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function cocherSansRepos(): Promise<void> {
    (racine().querySelector('mat-checkbox input') as HTMLElement).click();
    await fixture.whenStable();
  }

  /** Clicks the toggle carrying that label, whichever of the two groups holds it. */
  async function basculer(libelle: string): Promise<void> {
    const bouton = Array.from(racine().querySelectorAll('mat-button-toggle')).find(
      (each) => each.textContent!.trim() === libelle,
    );
    (bouton!.querySelector('button') as HTMLElement).click();
    await fixture.whenStable();
  }

  it('shows one line per animateur, the three states on screen', async () => {
    await rendre(planningDeuxJours());

    // Alice works both days and comes first; Bob rests day 1; Chloé was
    // unavailable on it.
    expect(noms()).toEqual(['Alice', 'Bob', 'Chloe']);
    // The legend uses the same colour classes, hence the `td` prefix.
    expect(racine().querySelectorAll('td.repos-cell-travaille')).toHaveLength(4);
    expect(racine().querySelectorAll('td.repos-cell-repos')).toHaveLength(1);
    expect(racine().querySelectorAll('td.repos-cell-indisponible')).toHaveLength(1);
  });

  it('announces every cell, since the colour alone says nothing out loud', async () => {
    await rendre(planningDeuxJours());

    expect(cellule(0, 0).getAttribute('aria-label')).toContain('2 h');
    expect(cellule(1, 0).getAttribute('aria-label')).toContain('jour de repos');
    expect(cellule(2, 0).getAttribute('aria-label')).toContain('indisponible');
  });

  it('counts, under each day, how many people are resting on it', async () => {
    await rendre(planningDeuxJours());

    const pied = Array.from(racine().querySelectorAll('tfoot .repos-footer-jour')).map((each) =>
      each.textContent!.trim(),
    );
    expect(pied).toEqual(['1', '0']);
  });

  it('filters by name and by "no rest day", and restores both from the URL', async () => {
    await rendre(planningDeuxJours());

    await saisirFiltre('bob');
    expect(noms()).toEqual(['Bob']);

    await saisirFiltre('');
    await cocherSansRepos();
    expect(noms()).toEqual(['Alice']);

    await rendre(planningDeuxJours(), { q: 'chl' });
    expect(noms()).toEqual(['Chloe']);

    await rendre(planningDeuxJours(), { sansRepos: '1' });
    expect(noms()).toEqual(['Alice']);
  });

  it('says so when a plan is available but the filter matches nobody', async () => {
    await rendre(planningDeuxJours(), { q: 'zzz' });

    expect(racine().textContent).toContain('Aucun animateur ne correspond au filtre');
  });

  it('tells the user to solve first when there is no plan at all', async () => {
    await rendre({ animateurs: [], postes: [], score: null });

    expect(racine().textContent).toContain('Aucune donnée de planning disponible');
  });

  it('exposes exactly one tab stop for the whole grid', async () => {
    await rendre(planningDeuxJours());

    // A grid where every cell is tabbable is a grid nobody tabs past.
    expect(racine().querySelectorAll('.repos-cell[tabindex="0"]')).toHaveLength(1);
    expect(cellule(0, 0).getAttribute('tabindex')).toBe('0');

    cellule(0, 0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    expect(cellule(1, 0).getAttribute('tabindex')).toBe('0');

    cellule(1, 0).dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await fixture.whenStable();
    expect(cellule(1, 1).getAttribute('tabindex')).toBe('0');
  });

  /** `?date=`: « Revoir les indisponibilités du jour » opens the grid on that day's column. */
  it('marks the column of ?date= and puts the tab stop on it', async () => {
    await rendre(planningDeuxJours(), { date: '2026-08-02' });
    await fixture.whenStable();

    const demandee = racine().querySelectorAll('th.repos-colonne-demandee');
    expect(demandee).toHaveLength(1);
    expect(demandee[0].getAttribute('aria-current')).toBe('date');
    expect(demandee[0].textContent).toContain('J2');
    expect(cellule(0, 1).getAttribute('tabindex')).toBe('0');
  });

  it('marks no column for a day the plan does not have', async () => {
    await rendre(planningDeuxJours(), { date: '2030-01-01' });

    expect(racine().querySelectorAll('th.repos-colonne-demandee')).toHaveLength(0);
    expect(cellule(0, 0).getAttribute('tabindex')).toBe('0');
  });

  it('keeps a tab stop when the filter drops the focused line', async () => {
    await rendre(planningDeuxJours());
    cellule(2, 0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();

    await saisirFiltre('alice');

    expect(racine().querySelectorAll('.repos-cell[tabindex="0"]')).toHaveLength(1);
  });

  it('leaves the hours out of the cells until the comfort density is asked for', async () => {
    // The duration is what made a column four times wider than its content; it
    // stays on the tooltip, which the accessible label already carried.
    await rendre(planningDeuxJours());
    expect(cellule(0, 0).textContent!.trim()).toBe('');

    await basculer('Heures');
    expect(cellule(0, 0).textContent!.trim()).toContain('2 h');

    await rendre(planningDeuxJours(), { densite: 'confort' });
    expect(cellule(0, 0).textContent!.trim()).toContain('2 h');
  });

  it('reads the days as a calendar, week-ends named and week boundaries drawn', async () => {
    // 2026-08-01 is a Saturday, 2026-08-02 a Sunday.
    await rendre(planningDeuxJours());

    const entetes = Array.from(racine().querySelectorAll('.repos-day-initiale')).map((each) =>
      each.textContent!.trim(),
    );
    expect(entetes).toEqual(['S', 'D']);
    expect(racine().querySelectorAll('th.repos-colonne-weekend')).toHaveLength(2);
  });

  it('draws the frise instead of the grid, one proportional bar per animateur', async () => {
    await rendre(planningDeuxJours(), { vue: 'frise' });

    expect(racine().querySelectorAll('.repos-cell')).toHaveLength(0);
    expect(racine().querySelectorAll('.repos-frise-ligne')).toHaveLength(3);
    // Alice works both days — one run; the two others change state on day 2.
    expect(racine().querySelectorAll('.repos-frise-segment')).toHaveLength(5);
    // The runs are weighted by their days, so every bar spans the same total.
    const premiere = racine().querySelector('.repos-frise-barre')!;
    expect((premiere.firstElementChild as HTMLElement).style.flexGrow).toBe('2');
  });

  it('offers the frise from the grid, and the grid back', async () => {
    await rendre(planningDeuxJours());

    await basculer('Frise');
    expect(racine().querySelectorAll('.repos-frise-ligne')).toHaveLength(3);
    // The density toggle only means something on the grid.
    expect(racine().textContent).not.toContain('Compact');

    await basculer('Grille');
    expect(racine().querySelectorAll('.repos-cell')).toHaveLength(6);
  });

  it('summarises the plan above it: the rest per day, and the strained lines', async () => {
    await rendre(planningDeuxJours());

    // One bar per day of the event, and the same figures as the footer.
    expect(racine().querySelectorAll('.repos-histogramme-jour')).toHaveLength(2);
    const tendues = Array.from(racine().querySelectorAll('.repos-tendue-nom')).map((each) =>
      each.textContent!.trim(),
    );
    expect(tendues[0]).toBe('Alice');
    expect(racine().textContent).toContain("2 j d'affilée");
  });

  it('counts the summary over the displayed lines, like the footer under them', async () => {
    await rendre(planningDeuxJours(), { q: 'bob' });

    expect(Array.from(racine().querySelectorAll('.repos-tendue-nom'))).toHaveLength(1);
  });

  it('reports the error instead of showing a stale grid', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: PlanningStateService,
          useValue: {
            loadForDisplay: vi.fn(async () => {
              throw new Error('boom');
            }),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ReposPage);
    await fixture.whenStable();

    expect(racine().textContent).toContain('boom');
    expect(racine().querySelectorAll('.repos-cell')).toHaveLength(0);
  });
});
