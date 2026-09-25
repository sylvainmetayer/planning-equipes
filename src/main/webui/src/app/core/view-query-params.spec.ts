import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NavigationStart, Params, Router, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import {
  NO_SORT,
  keepViewInQueryParams,
  optionalParam,
  readSort,
  sortQueryParams,
} from './view-query-params';

describe('readSort', () => {
  it('reads a column and its direction from the URL', () => {
    expect(readSort(convertToParamMap({ sort: 'total', dir: 'desc' }))).toEqual({
      active: 'total',
      direction: 'desc',
    });
  });

  it('falls back to no sort when the URL carries none', () => {
    expect(readSort(convertToParamMap({}))).toEqual(NO_SORT);
  });

  it('ignores a direction that is neither asc nor desc, rather than sorting on garbage', () => {
    expect(readSort(convertToParamMap({ sort: 'total', dir: 'sideways' }))).toEqual(NO_SORT);
  });

  it('ignores a column with no direction: half a sort is not a sort', () => {
    expect(readSort(convertToParamMap({ sort: 'total' }))).toEqual(NO_SORT);
  });

  it('keeps an unknown column: the comparator answers "equal" and the table stays in source order', () => {
    // A bookmarked link outliving the column it named must not fail the page.
    expect(readSort(convertToParamMap({ sort: 'colonne-supprimee', dir: 'asc' }))).toEqual({
      active: 'colonne-supprimee',
      direction: 'asc',
    });
  });
});

describe('sortQueryParams', () => {
  it('writes the column and its direction', () => {
    expect(sortQueryParams({ active: 'total', direction: 'asc' })).toEqual({
      sort: 'total',
      dir: 'asc',
    });
  });

  it('clears both params when the table is unsorted, instead of leaving a stale column behind', () => {
    expect(sortQueryParams(NO_SORT)).toEqual({ sort: null, dir: null });
    expect(sortQueryParams({ active: 'total', direction: '' })).toEqual({ sort: null, dir: null });
  });
});

describe('optionalParam', () => {
  it('keeps a filled value', () => {
    expect(optionalParam('durand')).toBe('durand');
  });

  it('drops an empty or blank value, so a cleared filter leaves the URL', () => {
    expect(optionalParam('')).toBeNull();
    expect(optionalParam('   ')).toBeNull();
    expect(optionalParam(null)).toBeNull();
    expect(optionalParam(undefined)).toBeNull();
  });
});

/**
 * The cause behind the focus bug, locked at its own level: mirroring the view
 * state must write the address bar and never run a navigation. A navigation
 * re-renders the page, and a re-render takes the focus out of the very field
 * the user is typing in — one character per click, which is how the defect
 * reached a business user.
 *
 * `filtres-et-tris.spec.ts` proves the symptom in a real browser, where the
 * focus actually lives; this one names the cause and runs in milliseconds.
 * Neither replaces the other.
 */
function mount(viewState: () => Params) {
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideRouter([]), provideLocationMocks()],
  });
  const router = TestBed.inject(Router);
  const location = TestBed.inject(Location);
  const navigations: string[] = [];
  router.events.subscribe((event) => {
    if (event instanceof NavigationStart) {
      navigations.push(event.url);
    }
  });
  TestBed.runInInjectionContext(() => keepViewInQueryParams(viewState));
  TestBed.tick();
  return { location, navigations };
}

describe('keepViewInQueryParams', () => {
  it("écrit l'état de vue dans la barre d'adresse", () => {
    const { location } = mount(() => ({ q: 'Alice', vue: 'COMPETENCES' }));

    expect(location.path()).toContain('q=Alice');
    expect(location.path()).toContain('vue=COMPETENCES');
  });

  it("ne déclenche aucune navigation du routeur — c'est elle qui coûtait le focus", () => {
    const filtre = signal('A');
    const { navigations } = mount(() => ({ q: filtre() }));

    filtre.set('Al');
    TestBed.tick();
    filtre.set('Ali');
    TestBed.tick();

    expect(navigations).toEqual([]);
  });

  it("remplace l'entrée d'historique au lieu d'en empiler une par frappe", () => {
    const filtre = signal('A');
    const { location } = mount(() => ({ q: filtre() }));

    filtre.set('Al');
    TestBed.tick();
    filtre.set('Ali');
    TestBed.tick();

    // Chaque écriture est un remplacement, jamais un empilement : le bouton
    // Retour quitte donc l'écran d'un coup, quel que soit le nombre de frappes.
    expect(location.path()).toContain('q=Ali');
    const ecritures = (location as unknown as { urlChanges: string[] }).urlChanges;
    expect(ecritures.every((url) => url.startsWith('replace: '))).toBe(true);
  });

  it('encode une valeur à espaces de façon à survivre au rechargement', () => {
    // `URLSearchParams` écrirait `+`, qu'Angular relit comme un plus littéral.
    const { location } = mount(() => ({ q: 'Alice E2E' }));

    expect(location.path()).toContain('q=Alice%20E2E');
  });

  it('efface un paramètre remis à sa valeur par défaut', () => {
    const filtre = signal<string | null>('Alice');
    const { location } = mount(() => ({ q: optionalParam(filtre()) }));

    filtre.set('');
    TestBed.tick();

    expect(location.path()).not.toContain('q=');
  });

  // A pasted address can carry anything, and the router accepts it: the effect
  // that mirrors the URL must not be the thing that dies on it. It used to
  // throw on the first run and stay dead for the life of the page.
  it('survit à une clé étrangère que personne ne peut décoder', () => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideLocationMocks()],
    });
    const location = TestBed.inject(Location);
    location.replaceState('/journee?q2=100%&drapeau&autre=a+b');

    expect(() => {
      TestBed.runInInjectionContext(() => keepViewInQueryParams(() => ({ q: 'Alice' })));
      TestBed.tick();
    }).not.toThrow();

    // Somebody else's keys go back out as they came in, the valueless one too.
    expect(location.path()).toContain('q2=100%');
    expect(location.path()).toContain('drapeau');
    expect(location.path()).toContain('autre=a+b');
    expect(location.path()).toContain('q=Alice');
  });

  // A page made of several components has several writers on one address: the
  // Journée writes the day and the rendering, the rail its line filter. Each
  // touches only the keys it names, or the second would erase the first on
  // every keystroke.
  it("laisse intactes les clés qu'un autre écrivain a posées", () => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideLocationMocks()],
    });
    const location = TestBed.inject(Location);
    location.replaceState('/journee?vue=rail&date=2026-07-22');
    const lignes = signal<string | null>('libres');

    TestBed.runInInjectionContext(() => keepViewInQueryParams(() => ({ lignes: lignes() })));
    TestBed.tick();

    expect(location.path()).toContain('vue=rail');
    expect(location.path()).toContain('date=2026-07-22');
    expect(location.path()).toContain('lignes=libres');

    lignes.set(null);
    TestBed.tick();

    expect(location.path()).toContain('vue=rail');
    expect(location.path()).not.toContain('lignes=');
  });
});
