import { describe, expect, it } from 'vitest';
import { Route } from '@angular/router';
import { routes } from '../app.routes';
import {
  MAX_PER_FAMILY,
  buildDestinationsNavigation,
  buildRaccourcisNavigation,
  chercherCommandes,
  isInputField,
  routePourTouche,
} from './keyboard-shortcuts';
import { Animateur, Creneau, Stand } from './models';

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
    dateNaissance: null,
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
    expect(parRoute.get('/stands')).toBe('s');
    expect(parRoute.get('/creneaux')).toBe('c');
    expect(parRoute.get('/')).toBe('g');
  });
});

describe('buildDestinationsNavigation', () => {
  const destinations = buildDestinationsNavigation();

  /** Every page `app.routes.ts` declares, redirects and parameterised routes aside. */
  function routesReelles(): string[] {
    const shell = routes.find((route) => route.path === '' && route.children);
    const all: Route[] = [...routes, ...(shell?.children ?? [])];
    return all
      .filter(
        (route) =>
          route.loadComponent !== undefined &&
          route.path !== undefined &&
          route.path !== 'login' &&
          !route.path.includes(':') &&
          !route.path.includes('*'),
      )
      .map((route) => (route.path === '' ? '/' : `/${route.path}`));
  }

  it('proposes every page of the application, so nothing is unreachable by keyboard', () => {
    expect(new Set(destinations.map((destination) => destination.route))).toEqual(
      new Set(routesReelles()),
    );
  });

  it('gives every destination a translated label and its own icon', () => {
    for (const destination of destinations) {
      expect(destination.label.length).toBeGreaterThan(0);
      // `arrow_forward` is the fallback of a route nobody described: it would
      // show up in the palette as an unlabelled, generic line.
      expect(destination.icon).not.toBe('arrow_forward');
    }
  });

  it('leaves out the espace animateur and the login page: they are not admin destinations', () => {
    const chemins = destinations.map((destination) => destination.route);
    expect(chemins).not.toContain('/login');
    expect(chemins.some((chemin) => chemin.includes(':'))).toBe(false);
  });

  it('shows the `g` sequence next to the pages that have one', () => {
    const animateurs = destinations.find((destination) => destination.route === '/animateurs');
    expect(animateurs?.raccourci).toBe('g a');
    const graphe = destinations.find((destination) => destination.route === '/graphe');
    expect(graphe?.raccourci).toBeUndefined();
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

  it('lists the pages only when nothing is typed', () => {
    const resultats = chercherCommandes('', sources);
    expect(resultats.length).toBe(sources.destinations.length);
    expect(resultats.every((commande) => commande.famille === 'navigation')).toBe(true);
  });

  it('finds an animateur by first name, and sends the user to their timeline', () => {
    const [trouve] = chercherCommandes('amelie', sources).filter(
      (commande) => commande.famille === 'animateur',
    );
    expect(trouve.label).toBe('Amélie Durand');
    expect(trouve.route).toBe('/timeline');
    expect(trouve.queryParams).toEqual({ animateur: 'a1' });
  });

  it('finds a stand ignoring accents, and opens the calendar filtered on it', () => {
    const [trouve] = chercherCommandes('mediatheque', sources).filter(
      (commande) => commande.famille === 'stand',
    );
    expect(trouve.label).toBe('Médiathèque');
    expect(trouve.route).toBe('/calendar');
    expect(trouve.queryParams).toEqual({ stand: 's2' });
  });

  it('finds a créneau by its date, and opens the calendar on that day', () => {
    const [trouve] = chercherCommandes('2026-07-19', sources).filter(
      (commande) => commande.famille === 'creneau',
    );
    expect(trouve.route).toBe('/calendar');
    expect(trouve.queryParams).toEqual({ month: '2026-07', date: '2026-07-19' });
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
