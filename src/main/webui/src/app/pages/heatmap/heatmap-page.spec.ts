// The two table builders are pure functions and tested as such below. The
// rendering half at the end exists for the grid itself: this table is the one
// screen of the application navigated with the arrow keys (roving tabindex),
// and a roving tabindex is exactly the kind of thing that works until the rows
// underneath change.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { Animateur, Creneau, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { buildAnimateurHeatmap, buildStandHeatmap, HeatmapPage } from './heatmap-page';

function creneau(overrides: Partial<Creneau> & { id: number; jour: number }): Creneau {
  return { date: '2026-08-01', heureDebut: '13:40', heureFin: '19:00', ...overrides };
}

function stand(id: string, typologiesProposees: string[] = []): Stand {
  return {
    id,
    nom: id,
    typologiesProposees,
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

function animateur(id: string): Animateur {
  return {
    id,
    prenom: id,
    nom: '',
    dateNaissance: '2000-01-01',
    manager: false,
    competences: {},
    joursIndisponibles: [],
    souhaits: [],
  };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

describe('buildStandHeatmap', () => {
  it('flags a stand with zero filled seats as critical (a coverage gap)', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([poste({ id: 'p1', creneau: c1, stand: stand('S1') })]);

    expect(table.rows).toHaveLength(1);
    expect(table.rows[0].cells[0]).toMatchObject({ level: 'critical', label: '0/1' });
  });

  it('flags a stand with some but not all seats filled as warning', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: c1, stand: stand('S1') }),
    ]);

    expect(table.rows[0].cells[0]).toMatchObject({ level: 'warning', label: '1/2' });
  });

  it('marks a fully-staffed stand as ok', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: c1, stand: stand('S1'), animateur: animateur('A') }),
    ]);

    expect(table.rows[0].cells[0]).toMatchObject({ level: 'ok', label: '1/1' });
  });

  it('marks a day with no créneau for that stand as none, distinct from a gap', () => {
    const table = buildStandHeatmap([
      poste({
        id: 'p1',
        creneau: creneau({ id: 1, jour: 1 }),
        stand: stand('S1'),
        animateur: animateur('A'),
      }),
      poste({ id: 'p2', creneau: creneau({ id: 2, jour: 2 }), stand: stand('S2') }),
    ]);

    const s1Row = table.rows.find((row) => row.id === 'S1')!;
    expect(s1Row.cells[1]).toMatchObject({ level: 'none', label: '' });
  });

  it('sorts stands alphabetically by name', () => {
    const c1 = creneau({ id: 1, jour: 1 });
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: c1, stand: stand('Zebre') }),
      poste({ id: 'p2', creneau: c1, stand: stand('Alpha') }),
    ]);

    expect(table.rows.map((row) => row.label)).toEqual(['Alpha', 'Zebre']);
  });
});

describe('buildAnimateurHeatmap', () => {
  it('marks a single poste that day as ok', () => {
    const table = buildAnimateurHeatmap([
      poste({
        id: 'p1',
        creneau: creneau({ id: 1, jour: 1 }),
        stand: stand('S1'),
        animateur: animateur('A'),
      }),
    ]);

    expect(table.rows[0].cells[0]).toMatchObject({ level: 'ok', label: '1' });
  });

  it('flags two postes the same day as warning and three or more as critical (overload)', () => {
    const jour1 = creneau({ id: 1, jour: 1 });
    const jour2 = creneau({ id: 2, jour: 1 });
    const jour3 = creneau({ id: 3, jour: 1 });
    const table = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: jour1, stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: jour2, stand: stand('S2'), animateur: animateur('A') }),
    ]);
    expect(table.rows[0].cells[0]).toMatchObject({ level: 'warning', label: '2' });

    const overloaded = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: jour1, stand: stand('S1'), animateur: animateur('A') }),
      poste({ id: 'p2', creneau: jour2, stand: stand('S2'), animateur: animateur('A') }),
      poste({ id: 'p3', creneau: jour3, stand: stand('S3'), animateur: animateur('A') }),
    ]);
    expect(overloaded.rows[0].cells[0]).toMatchObject({ level: 'critical', label: '3' });
  });

  it('excludes unassigned postes and never produces a row for them', () => {
    const table = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1') }),
    ]);

    expect(table.rows).toHaveLength(0);
  });

  it('ranks animateurs by descending total load, heaviest first', () => {
    const jour1 = creneau({ id: 1, jour: 1 });
    const jour2 = creneau({ id: 2, jour: 2 });
    const table = buildAnimateurHeatmap([
      poste({ id: 'p1', creneau: jour1, stand: stand('S1'), animateur: animateur('Light') }),
      poste({ id: 'p2', creneau: jour1, stand: stand('S2'), animateur: animateur('Heavy') }),
      poste({ id: 'p3', creneau: jour2, stand: stand('S3'), animateur: animateur('Heavy') }),
    ]);

    expect(table.rows.map((row) => row.id)).toEqual(['Heavy', 'Light']);
  });

  it('lists the distinct stands of an animateur in the row-header tooltip, alphabetically and without duplicates', () => {
    const table = buildAnimateurHeatmap([
      poste({
        id: 'p1',
        creneau: creneau({ id: 1, jour: 1 }),
        stand: stand('Zebre'),
        animateur: animateur('A'),
      }),
      poste({
        id: 'p2',
        creneau: creneau({ id: 2, jour: 2 }),
        stand: stand('Alpha'),
        animateur: animateur('A'),
      }),
      poste({
        id: 'p3',
        creneau: creneau({ id: 3, jour: 3 }),
        stand: stand('Alpha'),
        animateur: animateur('A'),
      }),
    ]);

    expect(table.rows[0].headerTooltip).toContain('2');
    expect(table.rows[0].headerTooltip).toContain('Alpha, Zebre');
  });

  it('adds the distinct game typologies of those stands to the tooltip, with one coloured badge each', () => {
    const table = buildAnimateurHeatmap(
      [
        poste({
          id: 'p1',
          creneau: creneau({ id: 1, jour: 1 }),
          stand: stand('Alpha', ['AMBIANCE']),
          animateur: animateur('A'),
        }),
        poste({
          id: 'p2',
          creneau: creneau({ id: 2, jour: 2 }),
          stand: stand('Beta', ['AMBIANCE', 'STRATEGIE']),
          animateur: animateur('A'),
        }),
      ],
      new Map([
        ['AMBIANCE', 'Ambiance'],
        ['STRATEGIE', 'Stratégie'],
      ]),
    );

    expect(table.rows[0].typologies.map((typologie) => typologie.label)).toEqual([
      'Ambiance',
      'Stratégie',
    ]);
    expect(table.rows[0].typologies[0].colorClass).not.toBe(table.rows[0].typologies[1].colorClass);
    expect(table.rows[0].headerTooltip).toContain('Ambiance, Stratégie');
  });

  it('leaves an animateur without any stand typologie without a badge', () => {
    const table = buildAnimateurHeatmap([
      poste({
        id: 'p1',
        creneau: creneau({ id: 1, jour: 1 }),
        stand: stand('Alpha'),
        animateur: animateur('A'),
      }),
    ]);

    expect(table.rows[0].typologies).toEqual([]);
  });

  it('leaves the stand rows without a header tooltip', () => {
    const table = buildStandHeatmap([
      poste({ id: 'p1', creneau: creneau({ id: 1, jour: 1 }), stand: stand('S1') }),
    ]);

    expect(table.rows[0].headerTooltip).toBe('');
  });
});

describe('HeatmapPage grid', () => {
  let fixture: ComponentFixture<HeatmapPage>;
  let loadForDisplay: ReturnType<typeof vi.fn>;

  function planning(postes: PosteAffectation[]): PlanningEvenement {
    return { postes } as unknown as PlanningEvenement;
  }

  /** Two animateurs over two days, so the grid has both rows and columns. */
  function planningDeuxAnimateurs(): PlanningEvenement {
    const j1 = creneau({ id: 1, jour: 1 });
    const j2 = creneau({ id: 2, jour: 2, date: '2026-08-02' });
    return planning([
      poste({ id: 'p1', creneau: j1, stand: stand('Tir'), animateur: animateur('Alice') }),
      poste({ id: 'p2', creneau: j2, stand: stand('Tir'), animateur: animateur('Alice') }),
      poste({ id: 'p3', creneau: j1, stand: stand('Dixit'), animateur: animateur('Bob') }),
    ]);
  }

  async function rendre(evenement: PlanningEvenement | null): Promise<void> {
    loadForDisplay = vi.fn(async () => evenement);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: PlanningStateService, useValue: { loadForDisplay } },
      ],
    });
    fixture = TestBed.createComponent(HeatmapPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function cellules(): HTMLElement[] {
    return Array.from(racine().querySelectorAll('td.heatmap-cell'));
  }

  function cellule(ligne: number, colonne: number): HTMLElement {
    return racine().querySelector(
      `[data-ligne="${ligne}"][data-colonne="${colonne}"]`,
    ) as HTMLElement;
  }

  /** The single cell the grid hands the focus to on Tab. */
  function celluleTabulable(): HTMLElement | null {
    return racine().querySelector('td.heatmap-cell[tabindex="0"]');
  }

  function toucher(ligne: number, colonne: number, key: string): void {
    cellule(ligne, colonne).dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
  }

  async function basculerVue(libelle: string): Promise<void> {
    const bouton = Array.from(racine().querySelectorAll('mat-button-toggle button')).find((each) =>
      each.textContent!.includes(libelle),
    ) as HTMLElement;
    bouton.click();
    await fixture.whenStable();
  }

  it('renders one row per stand and one column per event day', async () => {
    await rendre(planningDeuxAnimateurs());

    expect(
      Array.from(racine().querySelectorAll('.heatmap-row-label')).map((each) =>
        each.textContent!.trim(),
      ),
    ).toEqual(['Dixit', 'Tir']);
    expect(racine().querySelectorAll('.heatmap-day-header')).toHaveLength(2);
    // Every cell describes its day out loud: the colour alone means nothing.
    expect(cellules()[0].getAttribute('aria-label')).toBeTruthy();
  });

  it('says what to do rather than showing an empty grid when no planning exists', async () => {
    await rendre(planning([]));

    expect(racine().querySelector('.heatmap-table')).toBeNull();
    expect(racine().textContent!).toContain('Lancez une résolution depuis la page Solveur');
  });

  it('shows the load error instead of a stale grid', async () => {
    loadForDisplay = vi.fn(async () => {
      throw new Error('boom');
    });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: PlanningStateService, useValue: { loadForDisplay } },
      ],
    });
    fixture = TestBed.createComponent(HeatmapPage);
    await fixture.whenStable();

    expect(racine().querySelector('.heatmap-table')).toBeNull();
    const messages = Array.from(racine().querySelectorAll('.empty-hint')).map(
      (each) => each.textContent!,
    );
    expect(messages.some((text) => text.includes('boom'))).toBe(true);
  });

  it('offers the name filter on the animateur view only', async () => {
    await rendre(planningDeuxAnimateurs());
    expect(racine().querySelector('.heatmap-filter')).toBeNull();

    await basculerVue('Par animateur');
    expect(racine().querySelector('.heatmap-filter')).not.toBeNull();
    expect(
      Array.from(racine().querySelectorAll('.heatmap-row-label')).map((each) =>
        each.textContent!.trim(),
      ),
    ).toEqual(['Alice', 'Bob']);
  });

  it('exposes exactly one tab stop for the whole grid', async () => {
    await rendre(planningDeuxAnimateurs());

    // A grid where every cell is tabbable is a grid nobody tabs past.
    expect(racine().querySelectorAll('td.heatmap-cell[tabindex="0"]')).toHaveLength(1);
    expect(cellule(0, 0).getAttribute('tabindex')).toBe('0');
  });

  it('moves the tab stop with the arrow keys, and clamps it at the edges', async () => {
    await rendre(planningDeuxAnimateurs());

    toucher(0, 0, 'ArrowRight');
    await fixture.whenStable();
    expect(cellule(0, 1).getAttribute('tabindex')).toBe('0');

    toucher(0, 1, 'ArrowRight');
    await fixture.whenStable();
    // Last column: the focus stays instead of wrapping to the next row.
    expect(cellule(0, 1).getAttribute('tabindex')).toBe('0');

    toucher(0, 1, 'ArrowDown');
    await fixture.whenStable();
    expect(cellule(1, 1).getAttribute('tabindex')).toBe('0');

    toucher(1, 1, 'ArrowUp');
    toucher(0, 1, 'Home');
    await fixture.whenStable();
    expect(cellule(0, 0).getAttribute('tabindex')).toBe('0');

    toucher(0, 0, 'End');
    await fixture.whenStable();
    expect(cellule(0, 1).getAttribute('tabindex')).toBe('0');
  });

  it('ignores a key it does not handle, leaving the tab stop where it was', async () => {
    await rendre(planningDeuxAnimateurs());

    toucher(0, 0, 'a');
    await fixture.whenStable();

    expect(cellule(0, 0).getAttribute('tabindex')).toBe('0');
  });

  it('loses its only tab stop when the filter drops the row that held it', async () => {
    await rendre(planningDeuxAnimateurs());
    await basculerVue('Par animateur');

    toucher(0, 0, 'ArrowDown'); // second row, "Bob"
    await fixture.whenStable();
    expect(celluleTabulable()).not.toBeNull();

    const input = racine().querySelector('.heatmap-filter input') as HTMLInputElement;
    input.value = 'alice';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    // The filter has just removed the row the roving position pointed at. The
    // position is clamped on the rows actually displayed, so the grid keeps
    // exactly one keyboard entry point instead of falling out of the tab order.
    expect(racine().querySelectorAll('tbody tr')).toHaveLength(1);
    expect(celluleTabulable()).not.toBeNull();
  });
});
