import { describe, expect, it } from 'vitest';
import { pageRoutes } from '../core/testing/page-routes';
import { readAdjustmentsView } from '../pages/ad-hoc-constraints/reseau-paires';
import { ONGLETS_DEBUG, readOngletDebug } from '../pages/debug/debug';
import { ONGLETS_DIAGNOSTIC, readOnglet } from '../pages/diagnostic/diagnostic';
import { readDisponibilitesTab } from '../pages/disponibilites/declarations-filter';
import { ONGLETS_IMPORTS, readOngletImports } from '../pages/imports/imports';
import { JOURNEE_VIEWS, readView } from '../pages/journee/journee';
import { MARGIN_VIEW_PARAMS, readMarginView } from '../pages/marge/marge';
import { OPENINGS_VIEW_PARAMS, readOpeningsView } from '../pages/ouvertures/ouvertures';
import { ONGLETS_PARAMETRES, readOngletParametres } from '../pages/parametres/parametres';
import { NavLink, buildLegalLinks, buildNavGroups, buildOffMenuLinks } from './nav-groups';

const menuLinks = (): NavLink[] => buildNavGroups().flatMap((group) => group.links);

/** The non-null values of a « view → param » table: what the page ever writes in its URL. */
const written = (params: Readonly<Record<string, string | null>>): string[] =>
  Object.values(params).filter((value): value is string => value !== null);

/**
 * How each page with tabs reads its address, from the page's own reader: the
 * param, whether a value opens that tab or view (it reads back as itself), and
 * the values the page knows.
 */
const TAB_READERS: Record<
  string,
  { param: string; opens: (value: string) => boolean; values: readonly string[] }
> = {
  '/journee': { param: 'vue', opens: (v) => readView(v) === v, values: JOURNEE_VIEWS },
  '/ouvertures': {
    param: 'vue',
    opens: (v) => OPENINGS_VIEW_PARAMS[readOpeningsView(v)] === v,
    values: written(OPENINGS_VIEW_PARAMS),
  },
  '/marge': {
    param: 'mode',
    opens: (v) => MARGIN_VIEW_PARAMS[readMarginView(v)] === v,
    values: written(MARGIN_VIEW_PARAMS),
  },
  '/disponibilites': {
    param: 'onglet',
    opens: (v) => readDisponibilitesTab(v) === v,
    values: ['covoiturage'],
  },
  '/imports': {
    param: 'onglet',
    opens: (v) => readOngletImports(v) === v,
    values: ONGLETS_IMPORTS,
  },
  '/diagnostic': { param: 'onglet', opens: (v) => readOnglet(v) === v, values: ONGLETS_DIAGNOSTIC },
  '/ad-hoc-constraints': {
    param: 'vue',
    opens: (v) => readAdjustmentsView(v) === v,
    values: ['reseau'],
  },
  '/parametres': {
    param: 'onglet',
    opens: (v) => readOngletParametres(v) === v,
    values: ONGLETS_PARAMETRES,
  },
  '/debug': { param: 'onglet', opens: (v) => readOngletDebug(v) === v, values: ONGLETS_DEBUG },
};

describe('buildNavGroups', () => {
  // #709: the groups follow the moments of the cycle, in this order.
  it('groups the menu by moment of the cycle', () => {
    expect(buildNavGroups().map((group) => group.id)).toEqual([
      'accueil',
      'planning',
      'preparer',
      'construire',
      'diffuser',
      'aujourdhui',
      'administrer',
    ]);
  });

  it('lists neither Débogage nor the legal pages in a group', () => {
    const paths = menuLinks().map((link) => link.path);

    expect(paths).not.toContain('/debug');
    for (const legal of buildLegalLinks()) {
      expect(paths).not.toContain(legal.path);
    }
  });

  it('never uses one icon for two entries', () => {
    const icons = [...menuLinks(), ...buildOffMenuLinks(true), ...buildLegalLinks()].map(
      (link) => link.icon,
    );

    expect(icons.filter((icon, index) => icons.indexOf(icon) !== index)).toEqual([]);
  });

  it('never gives one letter to two destinations', () => {
    const letters = [...menuLinks(), ...buildOffMenuLinks(true)]
      .map((link) => link.shortcut)
      .filter((letter) => letter !== undefined);

    expect(letters.filter((letter, index) => letters.indexOf(letter) !== index)).toEqual([]);
  });

  // The palette reads this table, not `app.routes.ts`: a page served but known
  // to none of the three lists would be unreachable by keyboard, and an entry
  // naming no route would lead to the home page through the wildcard.
  it('knows every page of the application, and names no page that does not exist', () => {
    const known = [...menuLinks(), ...buildOffMenuLinks(false), ...buildLegalLinks()].map(
      (link) => link.path,
    );

    const byPath = (a: string, b: string): number => a.localeCompare(b);
    expect([...pageRoutes()].sort(byPath)).toEqual([...known].sort(byPath));
  });

  // A tab the page does not read is a palette entry landing on the default
  // view; a view the page reads but no tab declares is one the palette cannot find.
  it('declares every tab its page reads, and no tab its page would not open', () => {
    const withTabs = [...menuLinks(), ...buildOffMenuLinks(false)].filter((link) => link.tabs);

    expect(withTabs.map((link) => link.path).sort((a, b) => a.localeCompare(b))).toEqual(
      Object.keys(TAB_READERS).sort((a, b) => a.localeCompare(b)),
    );
    for (const link of withTabs) {
      const reader = TAB_READERS[link.path];
      for (const tab of link.tabs ?? []) {
        const address = `${link.path}?${tab.param}=${tab.value}`;
        expect(tab.param, address).toBe(reader.param);
        expect(reader.opens(tab.value), address).toBe(true);
      }
      const declared = (link.tabs ?? []).map((tab) => tab.value);
      for (const value of reader.values) {
        expect(declared, `${link.path}?${reader.param}=${value}`).toContain(value);
      }
    }
  });
});
