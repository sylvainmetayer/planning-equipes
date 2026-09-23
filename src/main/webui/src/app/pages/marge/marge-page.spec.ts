// The rendering half of « Marge disponible »: the grid is navigated with the
// arrow keys (roving tabindex) and each cell leads somewhere, and both are the
// kind of thing that works until the rows underneath change — a mode switch
// reshapes the whole table.

import { Location } from '@angular/common';
import { Signal, provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { CelluleMarge, ModeMarge, RapportMarge } from '../../core/models';
import { MargePage } from './marge-page';

/** Reaches the protected members the template binds to / user actions call. */
type PageInternals = {
  mode: Signal<ModeMarge>;
  viewChanged: Signal<boolean>;
  resetView(): void;
};

function cellule(overrides: Partial<CelluleMarge> = {}): CelluleMarge {
  return {
    date: '2026-07-10',
    jour: 1,
    debut: '09:00:00',
    fin: '12:00:00',
    creneauId: 1,
    sieges: 2,
    siegesPourvus: 0,
    besoin: 2,
    disponibles: 3,
    marge: 1,
    ...overrides,
  };
}

const SOIR = cellule({ debut: '20:00:00', fin: '23:00:00', creneauId: 2, marge: -2 });
const LENDEMAIN = cellule({ date: '2026-07-11', jour: 2, creneauId: 3, marge: 0 });

function rapport(mode: ModeMarge): RapportMarge {
  return {
    mode,
    tranches: [
      { debut: '09:00:00', fin: '12:00:00' },
      { debut: '20:00:00', fin: '23:00:00' },
    ],
    jours: [
      { date: '2026-07-10', jour: 1, cellules: [cellule(), SOIR], pireCellule: SOIR },
      { date: '2026-07-11', jour: 2, cellules: [LENDEMAIN], pireCellule: LENDEMAIN },
    ],
    animateursTotal: 3,
    cellulesDeficitaires: 1,
    pireCellule: SOIR,
    referentielsManquants: [],
    message: '1 tranche(s) en déficit, la plus tendue J1 20:00-23:00 à -2.',
  };
}

const VIDE: RapportMarge = {
  mode: 'AVANT',
  tranches: [],
  jours: [],
  animateursTotal: 0,
  cellulesDeficitaires: 0,
  pireCellule: null,
  referentielsManquants: ['CRENEAUX'],
  message: 'Aucun siège à couvrir : vérifiez les horaires des stands et la grille de créneaux.',
};

describe('MargePage', () => {
  let fixture: ComponentFixture<MargePage>;
  let get: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;
  let replaceState: ReturnType<typeof vi.fn>;

  async function monter(queryParams: Record<string, string> = {}, reponse?: RapportMarge) {
    get = vi.fn(
      async (chemin: string) => reponse ?? rapport(chemin.includes('apres') ? 'APRES' : 'AVANT'),
    );
    navigate = vi.fn(async () => true);
    replaceState = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get } },
        { provide: Router, useValue: { navigate } },
        { provide: Location, useValue: { path: () => '/marge', replaceState } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    fixture = TestBed.createComponent(MargePage);
    await fixture.whenStable();
    return fixture.componentInstance as unknown as PageInternals;
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function cellules(): HTMLElement[] {
    return Array.from(racine().querySelectorAll('td.marge-cell'));
  }

  function celluleAt(ligne: number, colonne: number): HTMLElement {
    return racine().querySelector(
      `td[data-ligne="${ligne}"][data-colonne="${colonne}"]`,
    ) as HTMLElement;
  }

  function toucher(ligne: number, colonne: number, key: string): void {
    celluleAt(ligne, colonne).dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('opens on the margin before any solve, and reads it from the server', async () => {
    const page = await monter();

    expect(page.mode()).toBe('AVANT');
    expect(page.viewChanged()).toBe(false);
    expect(get).toHaveBeenCalledWith('/api/marge?mode=avant');
    expect(racine().textContent!).toContain('Capacité brute');
  });

  it('seeds the mode from the URL, so a shared link opens the grid it named', async () => {
    const page = await monter({ mode: 'apres' });

    expect(page.mode()).toBe('APRES');
    expect(get).toHaveBeenCalledWith('/api/marge?mode=apres');
  });

  it('ignores a mode this page does not know, instead of a grid under the wrong toggle', async () => {
    const page = await monter({ mode: 'pendant' });

    expect(page.mode()).toBe('AVANT');
  });

  it('writes the chosen mode back to the URL (replacing, not pushing history)', async () => {
    const page = await monter({ mode: 'apres' });
    await fixture.whenStable();
    expect(replaceState).toHaveBeenCalledWith('/marge?mode=apres');

    replaceState.mockClear();
    page.resetView();
    await fixture.whenStable();

    expect(replaceState).toHaveBeenLastCalledWith('/marge');
  });

  it('draws one row per day and one column per timeslot, every day the same width', async () => {
    await monter();

    expect(racine().querySelectorAll('.heatmap-day-header')).toHaveLength(2);
    expect(racine().querySelectorAll('tbody tr')).toHaveLength(2);
    expect(cellules()).toHaveLength(4);
    // The second day holds no evening seat: its cell is empty, not missing.
    expect(celluleAt(1, 1).textContent!.trim()).toBe('');
    expect(celluleAt(0, 1).textContent!.trim()).toBe('-2');
    // Colour is never the only carrier: the cell announces its figures too.
    expect(celluleAt(0, 1).getAttribute('aria-label')).toContain('-2');
  });

  it('shows the server sentence rather than restating the grid', async () => {
    await monter();

    expect(racine().querySelector('[data-testid="marge-message"]')!.textContent!).toContain(
      'la plus tendue J1 20:00-23:00',
    );
  });

  // RGAA 7.5: the verdict is what the screen exists to give, so it is spoken
  // politely once the analysis lands — a status, not an interrupting alert.
  it('announces the verdict as a polite status', async () => {
    await monter();

    expect(racine().querySelector('[data-testid="marge-message"]')!.getAttribute('role')).toBe(
      'status',
    );
  });

  it('lists the worst timeslot of each day under the grid', async () => {
    await monter();

    const lignes = Array.from(
      racine().querySelectorAll('[data-testid="marge-synthese"] .marge-synthese-lien'),
    ).map((lien) =>
      Array.from(lien.querySelectorAll('span')).map((each) => each.textContent!.trim()),
    );

    expect(lignes).toEqual([
      ['J1 · 2026-07-10', '20:00-23:00', '-2'],
      ['J2 · 2026-07-11', '09:00-12:00', '0'],
    ]);
  });

  it('says what to fill in rather than showing an empty grid', async () => {
    await monter({}, VIDE);

    expect(racine().querySelector('.heatmap-table')).toBeNull();
    expect(racine().textContent!).toContain('Aucune tranche à comparer');
  });

  it('exposes exactly one tab stop for the whole grid, and moves it with the arrows', async () => {
    await monter();

    expect(racine().querySelectorAll('td.marge-cell[tabindex="0"]')).toHaveLength(1);
    expect(celluleAt(0, 0).getAttribute('tabindex')).toBe('0');

    toucher(0, 0, 'ArrowRight');
    await fixture.whenStable();
    expect(celluleAt(0, 1).getAttribute('tabindex')).toBe('0');

    toucher(0, 1, 'ArrowRight');
    await fixture.whenStable();
    // Last column: the focus stays instead of wrapping to the next row.
    expect(celluleAt(0, 1).getAttribute('tabindex')).toBe('0');

    toucher(0, 1, 'ArrowDown');
    await fixture.whenStable();
    expect(celluleAt(1, 1).getAttribute('tabindex')).toBe('0');

    toucher(1, 1, 'Home');
    await fixture.whenStable();
    expect(celluleAt(1, 0).getAttribute('tabindex')).toBe('0');

    toucher(1, 0, 'End');
    await fixture.whenStable();
    expect(celluleAt(1, 1).getAttribute('tabindex')).toBe('0');
  });

  it('ignores a key it does not handle, leaving the tab stop where it was', async () => {
    await monter();

    toucher(0, 0, 'a');
    await fixture.whenStable();

    expect(celluleAt(0, 0).getAttribute('tabindex')).toBe('0');
  });

  it('opens the stand openings of the day from a cell, before any solve', async () => {
    await monter();

    celluleAt(0, 1).click();
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/ouvertures'], {
      queryParams: { vue: 'journee', date: '2026-07-10' },
    });
  });

  it('opens the bench of the timeslot from a cell once a plan exists', async () => {
    await monter({ mode: 'apres' });

    toucher(0, 1, 'Enter');
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/diagnostic'], {
      queryParams: { onglet: 'banc', creneau: 2 },
    });
  });

  it('goes nowhere from a cell the grid holds no seat on', async () => {
    await monter();

    celluleAt(1, 1).click();
    await fixture.whenStable();

    expect(navigate).not.toHaveBeenCalled();
  });

  it('keeps its only tab stop when the mode reshapes the table under it', async () => {
    const page = await monter({ mode: 'apres' });
    toucher(0, 1, 'ArrowDown');
    await fixture.whenStable();

    page.resetView();
    await fixture.whenStable();

    expect(racine().querySelectorAll('td.marge-cell[tabindex="0"]')).toHaveLength(1);
  });
});
