// Global keyboard shortcuts: the destinations they reach, the entries the
// command palette proposes, and the rule deciding whether a key press belongs
// to the application or to whatever the user is typing.
//
// Kept as plain functions outside any component or service, so the table and
// the matching are unit-tested without rendering a dialog — and so the
// shortcut overlay, the palette and the help page all read the same table
// instead of drifting apart.

import { Route } from '@angular/router';
import { routes } from '../app.routes';
import { correspondAuFiltre } from './text-filter';
import { Animateur, Creneau, Stand } from './models';

/**
 * True when the key press is aimed at a field the user is typing in.
 *
 * <p>Same guard as the Konami listener (`admin-shell.ts`), widened to
 * `contenteditable` and to the ARIA text roles Material uses on its
 * combo-boxes: a single-letter shortcut firing while someone types a stand
 * name is the one failure mode that would make the whole feature hostile.</p>
 *
 * <p>It only ever gates the *single-key* shortcuts. A modifier combination
 * (Ctrl+K, Ctrl+Enter) cannot be confused with typing, and Ctrl+Enter is
 * precisely meant to be pressed from inside a field.</p>
 */
export function isInputField(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element || typeof element.tagName !== 'string') {
    return false;
  }
  if (['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName)) {
    return true;
  }
  if (element.isContentEditable) {
    return true;
  }
  const role = element.getAttribute?.('role');
  return role === 'textbox' || role === 'combobox' || role === 'searchbox';
}

/** One entry of the palette: a place to go, with what the user reads about it. */
export interface CommandePalette {
  /** Stable across renders; also the `track` key of the list. */
  id: string;
  /** Which family the entry comes from — drives the icon and the group label. */
  famille: 'navigation' | 'animateur' | 'stand' | 'creneau';
  label: string;
  /** Secondary line: the route, the stand's location, the slot's hours… */
  hint?: string;
  icon: string;
  route: string;
  queryParams?: Record<string, string>;
  /** The `g`+letter sequence reaching the same page, when there is one. */
  raccourci?: string;
}

/** Label, icon and optional `g`+letter of one route of the application. */
interface DefinitionRoute {
  label: string;
  icon: string;
  /** Letter pressed after `g`. */
  touche?: string;
}

/**
 * The `g`+letter table and the palette labels, in one place.
 *
 * <p>Labels reuse the navigation drawer's message ids on purpose: the palette
 * must name a page exactly as the menu does, and doing so adds no string to
 * translate.</p>
 *
 * <p>The letters read from the French label wherever that letter was free, and
 * fall back to a distinctive one where it was not — `g g` is the home page
 * (« État de l'édition »), `g l` *lance* the solver (`s` being taken by the
 * Stands), `g r` is « Réglages » (Paramètres, `p` being taken by the
 * Diagnostic and its *problèmes*), `g m` is the *mensuel* calendar and `g j`
 * the *journée*,
 * `g x` is Échanges (the crossing arrows of a swap, `e` being taken by
 * Emplacements). Pages without a letter are still reachable — through the
 * palette, which lists every route.</p>
 *
 * <p>Built lazily, never at module scope: `$localize` only resolves once
 * `main.ts` has loaded the catalog. Same reasoning as `buildNavGroups()`.</p>
 */
function buildDefinitionsRoutes(): Map<string, DefinitionRoute> {
  return new Map<string, DefinitionRoute>([
    [
      '/',
      { label: $localize`:@@nav.link.accueil:État de l'édition`, icon: 'checklist', touche: 'g' },
    ],
    [
      '/solveur',
      { label: $localize`:@@nav.link.solver:Solveur`, icon: 'play_circle', touche: 'l' },
    ],
    [
      '/notifications',
      {
        label: $localize`:@@nav.link.notifications:Notifications`,
        icon: 'notifications',
        touche: 'n',
      },
    ],
    [
      '/diagnostic',
      { label: $localize`:@@nav.link.diagnostic:Diagnostic`, icon: 'report_problem', touche: 'p' },
    ],
    [
      '/echanges',
      { label: $localize`:@@nav.link.echanges:Échanges`, icon: 'swap_horiz', touche: 'x' },
    ],
    [
      '/disponibilites',
      { label: $localize`:@@nav.link.disponibilites:Disponibilités`, icon: 'event_available' },
    ],
    ['/constraints', { label: $localize`:@@nav.link.constraints:Contraintes`, icon: 'fact_check' }],
    [
      '/ad-hoc-constraints',
      { label: $localize`:@@nav.link.adHocConstraints:Ajustements manuels`, icon: 'rule' },
    ],
    [
      '/instantanes',
      { label: $localize`:@@nav.link.snapshots:Instantanés`, icon: 'history', touche: 'i' },
    ],
    ['/aide', { label: $localize`:@@nav.link.aide:Aide`, icon: 'help_outline', touche: 'u' }],
    ['/nouveautes', { label: $localize`:@@nav.link.nouveautes:Nouveautés`, icon: 'new_releases' }],
    ['/editions', { label: $localize`:@@nav.link.editions:Éditions`, icon: 'layers' }],
    [
      '/ouvertures',
      {
        label: $localize`:@@nav.link.ouvertures:Ouvertures des stands`,
        icon: 'storefront',
        touche: 'o',
      },
    ],
    ['/jour-j', { label: $localize`:@@nav.link.jourJ:Mode jour J`, icon: 'emergency' }],
    ['/stands', { label: $localize`:@@nav.link.stands:Stands`, icon: 'storefront', touche: 's' }],
    [
      '/emplacements',
      { label: $localize`:@@nav.link.emplacements:Emplacements`, icon: 'place', touche: 'e' },
    ],
    [
      '/animateurs',
      { label: $localize`:@@nav.link.animateurs:Animateurs`, icon: 'groups', touche: 'a' },
    ],
    ['/competences', { label: $localize`:@@nav.link.competences:Compétences`, icon: 'grid_on' }],
    [
      '/creneaux',
      { label: $localize`:@@nav.link.creneaux:Créneaux`, icon: 'schedule', touche: 'c' },
    ],
    [
      '/typologies',
      { label: $localize`:@@nav.link.typologies:Typologies`, icon: 'category', touche: 't' },
    ],
    ['/imports', { label: $localize`:@@nav.link.imports:Imports`, icon: 'upload_file' }],
    ['/exports', { label: $localize`:@@nav.link.exports:Export`, icon: 'file_download' }],
    [
      '/publication',
      { label: $localize`:@@nav.link.publication:Publication`, icon: 'outgoing_mail' },
    ],
    [
      '/calendar',
      {
        label: $localize`:@@nav.link.calendar:Calendrier des affectations`,
        icon: 'calendar_month',
        touche: 'm',
      },
    ],
    ['/journee', { label: $localize`:@@nav.link.journee:Journée`, icon: 'view_day', touche: 'j' }],
    ['/hours', { label: $localize`:@@nav.link.hours:Heures`, icon: 'schedule', touche: 'h' }],
    [
      '/intendance',
      { label: $localize`:@@nav.link.intendance:Intendance des repas`, icon: 'restaurant' },
    ],
    [
      '/typologies-planning',
      { label: $localize`:@@nav.link.typologiesPlanning:Planning par typologie`, icon: 'category' },
    ],
    ['/equite', { label: $localize`:@@nav.link.equite:Équité`, icon: 'balance' }],
    ['/repos', { label: $localize`:@@nav.link.repos:Jours de repos`, icon: 'weekend' }],
    ['/heatmap', { label: $localize`:@@nav.link.heatmap:Heatmap de charge`, icon: 'grid_view' }],
    ['/marge', { label: $localize`:@@nav.link.marge:Marge disponible`, icon: 'exposure' }],
    ['/timeline', { label: $localize`:@@nav.link.timeline:Timeline animateur`, icon: 'timeline' }],
    ['/graphe', { label: $localize`:@@nav.link.graphe:Graphe`, icon: 'hub' }],
    [
      '/kpi',
      { label: $localize`:@@nav.link.kpi:Autopsie du planning`, icon: 'query_stats', touche: 'k' },
    ],
    [
      '/comparateur',
      { label: $localize`:@@nav.link.comparateur:Comparateur A/B`, icon: 'compare_arrows' },
    ],
    [
      '/parametres',
      { label: $localize`:@@nav.link.parametres:Paramètres`, icon: 'settings', touche: 'r' },
    ],
    ['/mcp-client', { label: $localize`:@@nav.link.mcp:MCP`, icon: 'smart_toy' }],
    ['/historique', { label: $localize`:@@nav.link.historique:Historique`, icon: 'manage_search' }],
    ['/debug', { label: $localize`:@@nav.link.debug:Débogage`, icon: 'bug_report', touche: 'd' }],
    [
      '/verrouillages',
      { label: $localize`:@@nav.link.verrouillages:Verrouillages`, icon: 'lock', touche: 'v' },
    ],
    // No letter: the free ones are few, and a screen used a few days a year
    // is reached through the palette's search.
    ['/consignes', { label: $localize`:@@nav.link.consignes:Consignes`, icon: 'gavel' }],
    [
      '/mentions-legales',
      { label: $localize`:@@nav.link.mentionsLegales:Mentions légales`, icon: 'gavel' },
    ],
    [
      '/politique-confidentialite',
      {
        label: $localize`:@@nav.link.confidentialite:Politique de confidentialité`,
        icon: 'privacy_tip',
      },
    ],
    [
      '/conditions-utilisation',
      { label: $localize`:@@nav.link.cgu:Conditions d'utilisation`, icon: 'handshake' },
    ],
    [
      '/declaration-accessibilite',
      { label: $localize`:@@nav.link.accessibilite:Accessibilité`, icon: 'accessibility_new' },
    ],
  ]);
}

/**
 * Routes the palette must not propose: they need a parameter, or a session it
 * is the opposite of — and the admin shell itself, a layout route whose `''`
 * child is the home. Listed as well, it doubled the home entry under the same
 * id, which stayed invisible as long as the home's label matched nothing.
 */
function estRoutePalette(route: Route): boolean {
  return (
    route.loadComponent !== undefined &&
    route.children === undefined &&
    route.path !== undefined &&
    route.path !== 'login' &&
    !route.path.includes(':') &&
    !route.path.includes('*')
  );
}

/**
 * Every reachable page, read from `app.routes.ts` rather than re-listed here.
 *
 * <p>That is what keeps the palette honest as the application grows: a route
 * added by anyone shows up in the palette on its own, labelled by its `title`
 * until someone gives it an entry in {@link buildDefinitionsRoutes} — a
 * missing label degrades one line of a list, where a forgotten registration
 * would have made a whole page unreachable by keyboard.</p>
 */
export function buildDestinationsNavigation(): CommandePalette[] {
  const definitions = buildDefinitionsRoutes();
  const shell = routes.find((route) => route.path === '' && route.children);
  const candidats = [...routes, ...(shell?.children ?? [])].filter(estRoutePalette);
  return candidats.map((route) => {
    const chemin = route.path === '' ? '/' : `/${route.path}`;
    const definition = definitions.get(chemin);
    return {
      id: `route:${chemin}`,
      famille: 'navigation' as const,
      label: definition?.label ?? (typeof route.title === 'string' ? route.title : chemin),
      hint: chemin,
      icon: definition?.icon ?? 'arrow_forward',
      route: chemin,
      raccourci: definition?.touche ? `g ${definition.touche}` : undefined,
    };
  });
}

/** One `g`+letter shortcut, as the overlay and the help page list it. */
export interface RaccourciNavigation {
  touche: string;
  route: string;
  label: string;
}

/** The `g`+letter table, in reading order, letter first. */
export function buildRaccourcisNavigation(): RaccourciNavigation[] {
  return [...buildDefinitionsRoutes()]
    .filter(([, definition]) => definition.touche !== undefined)
    .map(([route, definition]) => ({
      touche: definition.touche as string,
      route,
      label: definition.label,
    }))
    .sort((a, b) => a.touche.localeCompare(b.touche));
}

/** Route reached by `g` then this letter, or `null` when the letter is unassigned. */
export function routePourTouche(touche: string): string | null {
  const trouve = [...buildDefinitionsRoutes()].find(
    ([, definition]) => definition.touche === touche,
  );
  return trouve ? trouve[0] : null;
}

/** The referential the palette searches, as the store holds it. */
export interface SourcesPalette {
  destinations: readonly CommandePalette[];
  animateurs: readonly Animateur[];
  stands: readonly Stand[];
  creneaux: readonly Creneau[];
}

/**
 * How many entries of one family the palette shows at most.
 *
 * <p>153 animateurs matching « a » is not a result list, it is the referential
 * page rendered inside a dialog. The cap keeps the list scannable and the DOM
 * small; narrowing the query is the way to see the rest.</p>
 */
export const MAX_PER_FAMILY = 8;

/** Where an animateur found in the palette leads: their fiche, everything known about them on one page. */
function commandeAnimateur(animateur: Animateur): CommandePalette {
  return {
    id: `animateur:${animateur.id}`,
    famille: 'animateur',
    label: `${animateur.prenom} ${animateur.nom}`.trim(),
    hint: animateur.id,
    icon: 'person',
    route: `/animateurs/${animateur.id}`,
  };
}

/** A stand leads to the assignment calendar filtered on it — its schedule, in context. */
function commandeStand(stand: Stand): CommandePalette {
  return {
    id: `stand:${stand.id}`,
    famille: 'stand',
    label: stand.nom,
    hint: stand.emplacement?.nom ?? stand.id,
    icon: 'storefront',
    route: '/calendar',
    queryParams: { stand: stand.id },
  };
}

/** A créneau leads to the calendar opened on its day. */
function commandeCreneau(creneau: Creneau): CommandePalette {
  return {
    id: `creneau:${creneau.id}`,
    famille: 'creneau',
    label: `${creneau.date} ${creneau.heureDebut}-${creneau.heureFin}`,
    hint: $localize`:@@palette.creneau.jour:Jour ${creneau.jour}:jour:`,
    icon: 'schedule',
    route: '/calendar',
    queryParams: { month: creneau.date.slice(0, 7), date: creneau.date },
  };
}

/**
 * What the palette shows for a query: destinations first — the palette is
 * primarily a navigator — then the referential entries it matches.
 *
 * <p>An empty query lists the destinations only: proposing 24 animateurs to
 * someone who has just pressed Ctrl+K and typed nothing says nothing about
 * what they can do here.</p>
 */
export function chercherCommandes(query: string, sources: SourcesPalette): CommandePalette[] {
  const destinations = sources.destinations.filter((destination) =>
    correspondAuFiltre(query, [destination.label, destination.hint, destination.raccourci]),
  );
  if (!query.trim()) {
    return [...destinations];
  }
  const animateurs = sources.animateurs
    .filter((animateur) =>
      correspondAuFiltre(query, [animateur.prenom, animateur.nom, animateur.id]),
    )
    .slice(0, MAX_PER_FAMILY)
    .map(commandeAnimateur);
  const stands = sources.stands
    .filter((stand) => correspondAuFiltre(query, [stand.nom, stand.id, stand.emplacement?.nom]))
    .slice(0, MAX_PER_FAMILY)
    .map(commandeStand);
  const creneaux = sources.creneaux
    .filter((creneau) =>
      correspondAuFiltre(query, [
        creneau.date,
        creneau.heureDebut,
        creneau.heureFin,
        `jour ${creneau.jour}`,
      ]),
    )
    .slice(0, MAX_PER_FAMILY)
    .map(commandeCreneau);
  return [...destinations.slice(0, MAX_PER_FAMILY), ...animateurs, ...stands, ...creneaux];
}
