import { describe, expect, it } from 'vitest';
import { pageRoutes } from '../core/testing/page-routes';
import { readOngletAnimateurs } from '../pages/animateurs/animateurs-onglet';
import { readComparerView } from '../pages/comparer-editions/comparer-page';
import { readMesuresView } from '../pages/mesures/mesures-page';
import { ONGLETS_DEBUG, readOngletDebug } from '../pages/debug/debug';
import { ONGLETS_DIAGNOSTIC, readOnglet } from '../pages/diagnostic/diagnostic';
import { readHistoriqueTab } from '../pages/historique/historique';
import { readEchangesTab } from '../pages/echanges/echanges-filter';
import { ONGLETS_FICHIERS, readOngletFichiers } from '../pages/fichiers/fichiers';
import { IMPORT_CARDS, readImportCard } from '../pages/imports/imports';
import {
  PLANNING_VIEWS,
  readAxe,
  readOngletPlanning,
  readView,
  tabOf,
} from '../pages/journee/journee';
import { ONGLETS_PARAMETRES, readOngletParametres } from '../pages/parametres/parametres';
import { readOngletDiffuser } from '../pages/publication/onglet';
import { ONGLETS_REGLES, readOngletRegles } from '../pages/regles/regles';
import { readOngletStands } from '../pages/stands/stands-onglet';
import {
  NavLink,
  buildLegalLinks,
  buildNavGroups,
  buildOffMenuLinks,
  isFoldable,
} from './nav-groups';

const menuLinks = (): NavLink[] => buildNavGroups().flatMap((group) => group.links);

/**
 * How each page with tabs reads its address, from the page's own reader: the
 * param, whether a value opens that tab or view (it reads back as itself), and
 * the values the page knows. A page reading two params lists one reader each.
 */
interface TabReader {
  param: string;
  opens: (value: string) => boolean;
  values: readonly string[];
}

const TAB_READERS: Record<string, TabReader | readonly TabReader[]> = {
  '/journee': [
    {
      // « Jour » gathers the rail and the table; « Stand » and « Personne » are the two grids.
      param: 'vue',
      opens: (v) => readAxe(v) === v || tabOf(readView(v)) === v,
      values: PLANNING_VIEWS,
    },
    // « Calculer », the former Solveur, and its « Versions du plan ».
    {
      param: 'onglet',
      opens: (v) => readOngletPlanning(v) === v,
      values: ['calculer', 'versions'],
    },
  ],
  // The roster is the default tab; « covoiturage », the second tab of the
  // Disponibilités screen, opens that screen.
  '/animateurs': {
    param: 'onglet',
    opens: (v) =>
      readOngletAnimateurs(v) === v ||
      (v === 'covoiturage' && readOngletAnimateurs(v) === 'disponibilites'),
    values: ['competences', 'disponibilites', 'covoiturage'],
  },
  '/mesures': { param: 'vue', opens: (v) => readMesuresView(v) === v, values: ['indicateurs'] },
  // The requests are the default tab: only the statistics are named.
  '/echanges': { param: 'onglet', opens: (v) => readEchangesTab(v) === v, values: ['stats'] },
  '/fichiers': [
    // The Importer tab is reached through its cards, the page's default tab.
    { param: 'cible', opens: (v) => readImportCard(v) === v, values: IMPORT_CARDS },
    {
      param: 'onglet',
      opens: (v) => readOngletFichiers(v) === v,
      values: ONGLETS_FICHIERS.filter((onglet) => onglet !== 'importer'),
    },
  ],
  '/diagnostic': { param: 'onglet', opens: (v) => readOnglet(v) === v, values: ONGLETS_DIAGNOSTIC },
  '/publication': {
    param: 'onglet',
    opens: (v) => readOngletDiffuser(v) === v,
    values: ['envoyer', 'documents'],
  },
  // « connexions », the second tab of the Historique screen, opens that screen.
  '/parametres': {
    param: 'onglet',
    opens: (v) =>
      readOngletParametres(v) === v ||
      (readOngletParametres(v) === 'historique' && readHistoriqueTab(v) === v),
    values: [...ONGLETS_PARAMETRES, 'connexions'],
  },
  '/regles': { param: 'onglet', opens: (v) => readOngletRegles(v) === v, values: ONGLETS_REGLES },
  '/comparer': { param: 'vue', opens: (v) => readComparerView(v) === v, values: ['editions'] },
  '/debug': { param: 'onglet', opens: (v) => readOngletDebug(v) === v, values: ONGLETS_DEBUG },
  // The stands are the default tab: only the locations and the game categories are named.
  '/stands': {
    param: 'onglet',
    opens: (v) => readOngletStands(v) === v,
    values: ['lieux', 'typologies'],
  },
};

describe('buildNavGroups', () => {
  // Q48 (4.0): nine entries, in this order — the menu changes once, here.
  it('lists the nine entries of the menu, in the order of the cycle', () => {
    expect(buildNavGroups().map((group) => group.title)).toEqual([
      'Accueil',
      'Préparer',
      'Fichiers',
      'Planning',
      'Diagnostic',
      'Règles',
      'Diffuser',
      "Aujourd'hui",
      'Administrer',
    ]);
  });

  // The ceiling: a new screen is a tab or a sub-page of one of the nine.
  it('keeps the menu to nine entries', () => {
    expect(buildNavGroups()).toHaveLength(9);
  });

  // Each entry's sub-pages: the screens the 3.x menu listed apart.
  it('gathers the other screens as sub-pages of their entry', () => {
    const pagesOf = (id: string): string[] =>
      buildNavGroups()
        .find((group) => group.id === id)
        ?.links.map((link) => link.path) ?? [];

    expect(pagesOf('preparer')).toEqual(['/stands', '/calendrier', '/animateurs']);
    expect(pagesOf('diffuser')).toEqual(['/publication', '/echanges']);
    expect(pagesOf('administrer')).toEqual(['/parametres', '/editions', '/mesures', '/debug']);
    for (const group of buildNavGroups().filter((entry) => !isFoldable(entry))) {
      expect(group.links, group.id).toHaveLength(1);
      expect(group.title, group.id).toBe(group.links[0].label);
    }
  });

  // « Planning (avec Calculer) »: the solve is a tab of the Planning page.
  it('keeps Calculer and Versions as tabs of Planning', () => {
    const planning = menuLinks().find((link) => link.path === '/journee');
    expect(planning?.tabs?.filter((tab) => tab.param === 'onglet').map((tab) => tab.value)).toEqual(
      ['calculer', 'versions'],
    );
  });

  it('lists neither Aide, Nouveautés nor the legal pages in an entry', () => {
    const paths = menuLinks().map((link) => link.path);

    expect(paths).not.toContain('/aide');
    expect(paths).not.toContain('/nouveautes');
    for (const legal of buildLegalLinks()) {
      expect(paths).not.toContain(legal.path);
    }
  });

  it('never uses one icon for two entries', () => {
    const icons = [
      ...buildNavGroups()
        .filter(isFoldable)
        .map((group) => group.icon),
      ...[...menuLinks(), ...buildOffMenuLinks(true), ...buildLegalLinks()].map(
        (link) => link.icon,
      ),
    ];

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
      const entry = TAB_READERS[link.path];
      const readers: readonly TabReader[] = Array.isArray(entry) ? entry : [entry as TabReader];
      for (const tab of link.tabs ?? []) {
        const address = `${link.path}?${tab.param}=${tab.value}`;
        const reader = readers.find((candidate) => candidate.param === tab.param);
        expect(reader, address).toBeDefined();
        expect(reader?.opens(tab.value), address).toBe(true);
      }
      for (const reader of readers) {
        const declared = (link.tabs ?? [])
          .filter((tab) => tab.param === reader.param)
          .map((tab) => tab.value);
        for (const value of reader.values) {
          expect(declared, `${link.path}?${reader.param}=${value}`).toContain(value);
        }
      }
    }
  });
});
