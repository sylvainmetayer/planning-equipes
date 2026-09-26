import { describe, expect, it } from 'vitest';
import {
  MAX_PER_FAMILY,
  buildDestinationsNavigation,
  buildRaccourcisNavigation,
  chercherCommandes,
  isInputField,
  routePourTouche,
} from './keyboard-shortcuts';
import { Animateur, Creneau, Stand } from './models';
import { buildNavGroups } from '../shell/nav-groups';
import { pageRoutes } from './testing/page-routes';

function element(tag: string, attributs: Record<string, string> = {}): HTMLElement {
  const noeud = document.createElement(tag);
  for (const [nom, valeur] of Object.entries(attributs)) {
    noeud.setAttribute(nom, valeur);
  }
  return noeud;
}

function animateur(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

function stand(id: string, nom: string): Stand {
  return {
    id,
    nom,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function creneau(id: number, date: string, heureDebut: string, heureFin: string): Creneau {
  return { id, jour: 1, date, heureDebut, heureFin };
}

describe('estChampDeSaisie', () => {
  it('holds back the shortcuts inside every kind of text entry', () => {
    expect(isInputField(element('input'))).toBe(true);
    expect(isInputField(element('textarea'))).toBe(true);
    expect(isInputField(element('select'))).toBe(true);
    expect(isInputField(element('div', { role: 'textbox' }))).toBe(true);
    expect(isInputField(element('div', { role: 'combobox' }))).toBe(true);
  });

  it('lets them through everywhere else', () => {
    expect(isInputField(element('div'))).toBe(false);
    expect(isInputField(element('button'))).toBe(false);
    expect(isInputField(null)).toBe(false);
  });

  it('holds them back inside a contenteditable region', () => {
    const editable = element('div');
    Object.defineProperty(editable, 'isContentEditable', { value: true });
    expect(isInputField(editable)).toBe(true);
  });
});

describe('buildRaccourcisNavigation', () => {
  const raccourcis = buildRaccourcisNavigation();

  it('assigns each letter to exactly one page', () => {
    const touches = raccourcis.map((raccourci) => raccourci.touche);
    expect(new Set(touches).size).toBe(touches.length);
  });

  it('only uses single letters, so `g` + key stays a two-keystroke gesture', () => {
    for (const raccourci of raccourcis) {
      expect(raccourci.touche).toMatch(/^[a-z]$/);
    }
  });

  it('resolves each letter back to its route', () => {
    for (const raccourci of raccourcis) {
      expect(routePourTouche(raccourci.touche)).toBe(raccourci.route);
    }
    expect(routePourTouche('à')).toBeNull();
  });

  it('covers the reference-data pages, which are the most visited', () => {
    const parRoute = new Map(raccourcis.map((raccourci) => [raccourci.route, raccourci.touche]));
    expect(parRoute.get('/animateurs')).toBe('a');
    expect(parRoute.get('/creneaux')).toBe('c');
    expect(parRoute.get('/typologies')).toBe('t');
    expect(parRoute.get('/')).toBe('g');
  });

  // #709: a letter follows the initial of its label wherever it was free — the
  // letters nobody could guess (`g l`, `g r`, `g x`) are gone.
  it('reads each letter from the label it opens, the home aside', () => {
    for (const raccourci of raccourcis.filter((r) => r.route !== '/')) {
      const initiale = raccourci.label
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .charAt(0)
        .toLowerCase();
      expect(initiale, raccourci.label).toBe(raccourci.touche);
    }
  });
});

describe('buildDestinationsNavigation', () => {
  const destinations = buildDestinationsNavigation();

  it('proposes every page of the application, so nothing is unreachable by keyboard', () => {
    expect(new Set(destinations.map((destination) => destination.route))).toEqual(
      new Set(pageRoutes()),
    );
  });

  it('gives every destination a translated label and an icon', () => {
    for (const destination of destinations) {
      expect(destination.label.length).toBeGreaterThan(0);
      expect(destination.icon.length).toBeGreaterThan(0);
    }
  });

  it('leaves out the espace animateur and the login page: they are not admin destinations', () => {
    const chemins = destinations.map((destination) => destination.route);
    expect(chemins).not.toContain('/login');
    expect(chemins.some((chemin) => chemin.includes(':'))).toBe(false);
  });

  it('shows the `g` sequence next to the pages that have one', () => {
    const animateurs = destinations.find((destination) => destination.id === 'route:/animateurs');
    expect(animateurs?.raccourci).toBe('g a');
    const graphe = destinations.find((destination) => destination.id === 'route:/graphe');
    expect(graphe?.raccourci).toBeUndefined();
  });

  it('names a tab « Page › Tab » and opens it through its query param', () => {
    const former = destinations.find((destination) => destination.id.endsWith('onglet=former'));
    expect(former?.label).toBe('Diagnostic › À former');
    expect(former?.route).toBe('/diagnostic');
    expect(former?.queryParams).toEqual({ onglet: 'former' });
  });

  it('offers the Quarkus Dev UI in development only, as an external address', () => {
    expect(destinations.some((destination) => destination.route === '/q/dev-ui')).toBe(false);
    const devUi = buildDestinationsNavigation(true).find(
      (destination) => destination.route === '/q/dev-ui',
    );
    expect(devUi?.externe).toBe(true);
  });
});

describe('chercherCommandes', () => {
  const sources = {
    destinations: buildDestinationsNavigation(),
    animateurs: [animateur('a1', 'Amélie', 'Durand'), animateur('a2', 'Bruno', 'Lefèvre')],
    stands: [stand('s1', 'Village des Enfants'), stand('s2', 'Médiathèque')],
    creneaux: [
      creneau(1, '2026-07-18', '10:00', '12:00'),
      creneau(2, '2026-07-19', '14:00', '16:00'),
    ],
  };

  // #709: an empty query is the menu, in the menu's order — no tab, no
  // legal page, no screen listed in no group.
  it('lists the menu, in its order, when nothing is typed', () => {
    const resultats = chercherCommandes('', sources);
    expect(resultats.map((commande) => commande.route)).toEqual(
      buildNavGroups().flatMap((group) => group.links.map((link) => link.path)),
    );
    expect(resultats.some((commande) => commande.route === '/mentions-legales')).toBe(false);
    expect(resultats.some((commande) => commande.route === '/debug')).toBe(false);
  });

  it.each([
    ['problèmes', '/diagnostic', { onglet: 'problemes' }],
    ['probl', '/diagnostic', { onglet: 'problemes' }],
    ['mural', '/parametres', { onglet: 'mural' }],
    ['légaux', '/parametres', { onglet: 'legaux' }],
    ['yaml', '/fichiers', { cible: 'scenario' }],
    ['exemples', '/fichiers', { cible: 'exemples' }],
    ['démonstration', '/fichiers', { cible: 'exemples' }],
    ['archive', '/fichiers', { onglet: 'archive' }],
    ['date simulée', '/parametres', { onglet: 'instance' }],
    ['horloge', '/parametres', { onglet: 'instance' }],
  ])('finds « %s » and opens the right tab', (query, route, queryParams) => {
    const trouve = chercherCommandes(query, sources).find(
      (commande) => commande.route === route && commande.queryParams !== undefined,
    );
    expect(trouve?.queryParams).toEqual(queryParams);
  });

  // The bench left the Diagnostic for the Siège panel of the Journée: the
  // word a reader remembers still leads there, first.
  it('finds the Journée on « banc », where the bench went', () => {
    const resultats = chercherCommandes('banc', sources);
    expect(resultats[0]?.route).toBe('/journee');
    expect(resultats.some((commande) => commande.queryParams?.['onglet'] === 'banc')).toBe(false);
  });

  // Débogage sits in no group: served everywhere, found on a query.
  it.each(['débogage', 'swagger', 'mailpit', 'json'])('finds Débogage on « %s »', (query) => {
    const resultats = chercherCommandes(query, sources);
    expect(resultats.some((commande) => commande.route === '/debug')).toBe(true);
  });

  it('finds a legal page once asked for', () => {
    const resultats = chercherCommandes('mentions', sources);
    expect(resultats.some((commande) => commande.route === '/mentions-legales')).toBe(true);
  });

  it('finds an animateur by first name, and sends the user to their fiche', () => {
    const found = chercherCommandes('amelie', sources).find(
      (commande) => commande.famille === 'animateur',
    );
    expect(found?.label).toBe('Amélie Durand');
    expect(found?.route).toBe('/animateurs/a1');
    expect(found?.queryParams).toBeUndefined();
  });

  it('finds a stand ignoring accents, and opens the Planning page filtered on it', () => {
    const found = chercherCommandes('mediatheque', sources).find(
      (commande) => commande.famille === 'stand',
    );
    expect(found?.label).toBe('Médiathèque');
    expect(found?.route).toBe('/journee');
    expect(found?.queryParams).toEqual({ stand: 's2' });
  });

  it('finds a créneau by its date, and opens the Planning page on that day', () => {
    const found = chercherCommandes('2026-07-19', sources).find(
      (commande) => commande.famille === 'creneau',
    );
    expect(found?.route).toBe('/journee');
    expect(found?.queryParams).toEqual({ date: '2026-07-19' });
  });

  it('finds a page by its label', () => {
    const resultats = chercherCommandes('heatmap', sources);
    expect(resultats.some((commande) => commande.route === '/heatmap')).toBe(true);
  });

  it('caps each family, so a one-letter query stays a list and not a table', () => {
    const beaucoup = {
      ...sources,
      animateurs: Array.from({ length: 40 }, (_, index) =>
        animateur(`a${index}`, 'Alex', `Nom${index}`),
      ),
    };
    const animateurs = chercherCommandes('alex', beaucoup).filter(
      (commande) => commande.famille === 'animateur',
    );
    expect(animateurs).toHaveLength(MAX_PER_FAMILY);
  });

  it('gives every entry a unique id, so the list can be tracked', () => {
    const resultats = chercherCommandes('a', sources);
    const ids = resultats.map((commande) => commande.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('returns nothing rather than everything when no entry matches', () => {
    expect(chercherCommandes('zzzzzz', sources)).toHaveLength(0);
  });
});
