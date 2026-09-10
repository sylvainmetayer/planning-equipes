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

    const pied = Array.from(racine().querySelectorAll('tfoot .repos-total-cell')).map((each) =>
      each.textContent!.trim(),
    );
    expect(pied.slice(0, 2)).toEqual(['1', '0']);
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

  it('keeps a tab stop when the filter drops the focused line', async () => {
    await rendre(planningDeuxJours());
    cellule(2, 0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();

    await saisirFiltre('alice');

    expect(racine().querySelectorAll('.repos-cell[tabindex="0"]')).toHaveLength(1);
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
