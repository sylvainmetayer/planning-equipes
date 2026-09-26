// The treemap over a small plan — the optional rendering of the Planning
// page's « Par stand » axis: the tiles, the zoom and its breadcrumb, the
// table, and the view state it keeps in the URL next to the page's keys. The
// geometry itself is tested in treemap.spec.ts.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Creneau, Emplacement, PosteAffectation, Stand } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { RepartitionHeuresView } from './repartition-heures-vue';

const PLACE: Emplacement = { id: 'PLACE', nom: 'Place', latitude: null, longitude: null };

function stand(id: string, typologies: string[]): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: typologies,
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: PLACE,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

const TIR = stand('Tir', ['AMB']);
const DIXIT = stand('Dixit', ['AMB', 'STRAT']);
const IDLE = stand('Idle', ['AMB']);
const SLOT: Creneau = {
  id: 1,
  jour: 1,
  date: '2026-07-08',
  heureDebut: '10:00',
  heureFin: '14:00',
};

function seat(id: string, of: Stand, filled: boolean): PosteAffectation {
  return {
    id,
    stand: of,
    creneau: SLOT,
    animateur: filled
      ? {
          id: 'A',
          prenom: 'X',
          nom: 'Y',
          dateNaissance: '1990-01-01',
          manager: false,
          competences: {},
          souhaits: [],
          joursIndisponibles: [],
        }
      : null,
  };
}

async function setUp(
  queryParams: Record<string, string> = {},
  postes?: PosteAffectation[],
  options: { pending?: boolean; emplacement?: string } = {},
) {
  const replaceState = vi.fn();
  // Created as the page switches renderings, the treemap reads the address
  // bar, not the router's snapshot of the navigation that built the page.
  const query = new URLSearchParams(queryParams).toString();
  const chemin = query ? `/journee?${query}` : '/journee';
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: PlanningStateService,
        useValue: {
          loadForDisplay: vi.fn(() =>
            options.pending
              ? new Promise(() => undefined)
              : Promise.resolve({
                  animateurs: [],
                  postes: postes ?? [
                    seat('1', TIR, true),
                    seat('2', TIR, false),
                    seat('3', DIXIT, true),
                  ],
                  score: null,
                }),
          ),
        },
      },
      {
        provide: ReferenceDataStore,
        useValue: {
          reload: vi.fn(async () => undefined),
          stands: signal([TIR, DIXIT, IDLE]),
          typologies: signal([
            { id: 'AMB', label: 'Ambiance' },
            { id: 'STRAT', label: 'Stratégie' },
          ]),
        },
      },
      { provide: Location, useValue: { path: () => chemin, replaceState } },
    ],
  });
  const fixture = TestBed.createComponent(RepartitionHeuresView);
  const opened: { standId: string; date: string | null }[] = [];
  fixture.componentInstance.standOuvert.subscribe((demande) => opened.push(demande));
  if (options.emplacement) {
    fixture.componentRef.setInput('emplacementFilter', options.emplacement);
  }
  fixture.detectChanges();
  if (!options.pending) {
    await fixture.whenStable();
  }
  fixture.detectChanges();
  const page = fixture.componentInstance as unknown as { setPeriod(value: string): void };
  return { fixture, page, replaceState, opened, element: fixture.nativeElement as HTMLElement };
}

/** Where a tile sits, in percentages of the treemap, as its inline style says. */
function topPercent(tile: HTMLElement): number {
  return Number.parseFloat(tile.style.top);
}

function tileLabels(element: HTMLElement): string[] {
  return Array.from(element.querySelectorAll<HTMLButtonElement>('button.repartition-tile')).map(
    (button) => button.getAttribute('aria-label') ?? '',
  );
}

describe('RepartitionHeuresView', () => {
  beforeEach(() => TestBed.resetTestingModule());
  afterEach(() => vi.unstubAllGlobals());

  it('draws the emplacement as one tile, coloured by its coverage', async () => {
    const { element } = await setUp();
    const tiles = element.querySelectorAll<HTMLButtonElement>('button.repartition-tile');
    expect(tiles).toHaveLength(1);
    // 8 h filled out of 12 h: 66 %, critical.
    expect(tiles[0].classList).toContain('repartition-critical');
    expect(tileLabels(element)[0]).toContain(
      'Place — 12 h à pourvoir, 8 h pourvues, 4 h vides (66 %)',
    );
    expect(element.querySelectorAll('.repartition-inner')).toHaveLength(2);
  });

  it('zooms into a group on click, and the breadcrumb leads back', async () => {
    const { fixture, element } = await setUp();
    element.querySelector<HTMLButtonElement>('button.repartition-tile')!.click();
    fixture.detectChanges();

    expect(tileLabels(element).map((label) => label.split(' — ')[0])).toEqual(['Tir', 'Dixit']);
    const back = element.querySelector<HTMLButtonElement>('.repartition-breadcrumb button')!;
    expect(back.textContent!.trim()).toBe('Édition');
    back.click();
    fixture.detectChanges();
    expect(tileLabels(element)).toHaveLength(1);
  });

  it("asks the page for a stand's day on a click once zoomed", async () => {
    const { element, opened } = await setUp({ zoom: 'e:PLACE', jourTreemap: '2026-07-08' });
    element.querySelector<HTMLButtonElement>('button.repartition-tile')!.click();
    expect(opened).toEqual([{ standId: 'Tir', date: '2026-07-08' }]);
  });

  it("narrows to the page's location filter, and owns no location key of its own", async () => {
    const { element, replaceState } = await setUp({}, undefined, { emplacement: 'ailleurs' });
    expect(element.textContent).toContain('Aucun siège à pourvoir');
    expect(replaceState).toHaveBeenLastCalledWith('/journee');
  });

  it('files the multi-typologie stand under its combination', async () => {
    const { element } = await setUp({ regroupement: 'typologie' });
    const groups = tileLabels(element).map((label) => label.split(' — ')[0]);
    expect(groups).toEqual(['Ambiance', 'Ambiance + Stratégie']);
  });

  it('lists every stand in the table, the ones at 0 h included', async () => {
    const { element } = await setUp();
    const rows = Array.from(element.querySelectorAll('.repartition-table tbody th')).map((cell) =>
      cell.textContent!.trim(),
    );
    expect(rows).toEqual(['Place', 'Tir', 'Dixit', 'Idle']);
  });

  it("keeps the grouping, the period and the zoom in the URL, next to the page's keys", async () => {
    const { replaceState } = await setUp({
      axe: 'stand',
      regroupement: 'typologie',
      semaine: '2026-07-06',
      zoom: 't:AMB',
    });
    expect(replaceState).toHaveBeenLastCalledWith(
      '/journee?axe=stand&regroupement=typologie&semaine=2026-07-06&zoom=t%3AAMB',
    );
  });

  it('reads a mid-week date in ?semaine= as its week', async () => {
    const { element, replaceState } = await setUp({ semaine: '2026-07-08' });
    expect(replaceState).toHaveBeenLastCalledWith('/journee?semaine=2026-07-06');
    expect(tileLabels(element)).toHaveLength(1);
  });

  it('keeps the zoom asked for in the URL while the plan loads', async () => {
    const { replaceState } = await setUp({ zoom: 'e:PLACE' }, undefined, { pending: true });
    expect(replaceState).toHaveBeenLastCalledWith('/journee?zoom=e%3APLACE');
  });

  it('drops a zoom the new period empties, and does not revive it afterwards', async () => {
    const { fixture, page, replaceState } = await setUp({ zoom: 'e:PLACE' });
    expect(replaceState).toHaveBeenLastCalledWith('/journee?zoom=e%3APLACE');

    // The day of its period under a key of its own: `date` is the page's day.
    page.setPeriod('d:2026-07-09');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(replaceState).toHaveBeenLastCalledWith('/journee?jourTreemap=2026-07-09');

    page.setPeriod('all');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(replaceState).toHaveBeenLastCalledWith('/journee');
  });

  it('lays the tiles out at their rendered size: title room and labels in real pixels', async () => {
    // A phone-width card: 240 × 144 px.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private readonly callback: ResizeObserverCallback) {}
        observe(): void {
          queueMicrotask(() =>
            this.callback(
              [{ contentRect: { width: 240, height: 144 } } as ResizeObserverEntry],
              this as unknown as ResizeObserver,
            ),
          );
        }
        // Material's form fields observe too, and let go one element at a time.
        unobserve(): void {}
        disconnect(): void {}
      },
    );
    const { fixture, element } = await setUp();
    await fixture.whenStable();
    fixture.detectChanges();

    const inner = Array.from(element.querySelectorAll<HTMLElement>('.repartition-inner'));
    expect(inner).toHaveLength(2);
    // The group's two-line title keeps its 40 px out of 144.
    for (const tile of inner) {
      expect(topPercent(tile)).toBeGreaterThanOrEqual((40 / 144) * 100 - 1e-9);
    }
    // Tir (8 h) keeps its label; Dixit (4 h) is a third of 234 px wide, too narrow for one.
    const labels = inner.map((tile) => tile.querySelector('.repartition-tile-name')?.textContent);
    expect(labels).toEqual(['Tir', undefined]);
  });

  it('says so when no plan is saved, and when the period holds no seat', async () => {
    const empty = await setUp({}, []);
    expect(empty.element.textContent).toContain('Aucun planning enregistré');
    TestBed.resetTestingModule();
    const otherDay = await setUp({ jourTreemap: '2026-07-09' });
    expect(otherDay.element.textContent).toContain('Aucun siège à pourvoir');
  });
});
