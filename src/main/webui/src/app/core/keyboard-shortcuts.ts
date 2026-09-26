// Global keyboard shortcuts: the destinations they reach, the entries the
// command palette proposes, and the rule deciding whether a key press belongs
// to the application or to whatever the user is typing.
//
// Kept as plain functions outside any component or service, so the table and
// the matching are unit-tested without rendering a dialog — and so the
// shortcut overlay, the palette and the help page all read the same table
// instead of drifting apart.

import { NavLink, buildLegalLinks, buildNavGroups, buildOffMenuLinks } from '../shell/nav-groups';
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
  /** Served by the backend (the Quarkus Dev UI): opened in a new tab, never routed to. */
  externe?: boolean;
  /**
   * Proposed on an empty query: the menu's entries, in the menu's order. A
   * tab, a screen listed in no group and a legal page only answer a query.
   */
  menu?: boolean;
  /** Words matched beyond the label and the hint. */
  motsCles?: string;
}

/**
 * Every destination the palette knows, in the menu's order: each menu entry
 * followed by its tabs, then the screens listed in no group (Débogage, the
 * news…), then the legal pages. Read from `shell/nav-groups.ts`, the one table
 * the drawer renders too, so the palette names a page exactly as the menu
 * does and a tab added there is found here.
 *
 * <p>Built lazily, never at module scope: `$localize` only resolves once
 * `main.ts` has loaded the catalog. Same reasoning as `buildNavGroups()`.</p>
 */
export function buildDestinationsNavigation(devMode = false): CommandePalette[] {
  const menu = buildNavGroups().flatMap((group) => group.links);
  return [
    ...menu.flatMap((link) => destinations(link, true)),
    ...buildOffMenuLinks(devMode).flatMap((link) => destinations(link, false)),
    ...buildLegalLinks().flatMap((link) => destinations(link, false)),
  ];
}

/** A link and its tabs, as palette entries. */
function destinations(link: NavLink, menu: boolean): CommandePalette[] {
  const page: CommandePalette = {
    id: `route:${link.path}`,
    famille: 'navigation',
    label: link.label,
    hint: link.path,
    icon: link.icon,
    route: link.path,
    raccourci: link.shortcut ? `g ${link.shortcut}` : undefined,
    externe: link.externe,
    menu,
    motsCles: link.keywords,
  };
  const onglets = (link.tabs ?? []).map((onglet): CommandePalette => ({
    id: `route:${link.path}?${onglet.param}=${onglet.value}`,
    famille: 'navigation',
    label: `${link.label} › ${onglet.label}`,
    hint: `${link.path}?${onglet.param}=${onglet.value}`,
    icon: link.icon,
    route: link.path,
    queryParams: { [onglet.param]: onglet.value },
    motsCles: [link.keywords, onglet.keywords].filter(Boolean).join(' ') || undefined,
  }));
  return [page, ...onglets];
}

/** One `g`+letter shortcut, as the overlay and the help page list it. */
export interface RaccourciNavigation {
  touche: string;
  route: string;
  label: string;
}

/** The `g`+letter table, in reading order, letter first. */
export function buildRaccourcisNavigation(): RaccourciNavigation[] {
  return linksWithShortcut()
    .map((link) => ({ touche: link.shortcut as string, route: link.path, label: link.label }))
    .sort((a, b) => a.touche.localeCompare(b.touche));
}

/**
 * The `g`+letter table as a map, built on the first key press rather than at
 * import: `$localize` in the tables it reads only resolves once `main.ts` has
 * loaded the catalog. The letters do not change while the application runs.
 */
let routesByKey: ReadonlyMap<string, string> | null = null;

/** Route reached by `g` then this letter, or `null` when the letter is unassigned. */
export function routePourTouche(touche: string): string | null {
  routesByKey ??= new Map(linksWithShortcut().map((link) => [link.shortcut as string, link.path]));
  return routesByKey.get(touche) ?? null;
}

function linksWithShortcut(): NavLink[] {
  return [...buildNavGroups().flatMap((group) => group.links), ...buildOffMenuLinks(false)].filter(
    (link) => link.shortcut !== undefined,
  );
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

/** A stand leads to the Planning page filtered on it — its schedule, in context. */
function commandeStand(stand: Stand): CommandePalette {
  return {
    id: `stand:${stand.id}`,
    famille: 'stand',
    label: stand.nom,
    hint: stand.emplacement?.nom ?? stand.id,
    icon: 'storefront',
    route: '/journee',
    queryParams: { stand: stand.id },
  };
}

/** A créneau leads to the Planning page opened on its day. */
function commandeCreneau(creneau: Creneau): CommandePalette {
  return {
    id: `creneau:${creneau.id}`,
    famille: 'creneau',
    label: `${creneau.date} ${creneau.heureDebut}-${creneau.heureFin}`,
    hint: $localize`:@@palette.creneau.jour:Jour ${creneau.jour}:jour:`,
    icon: 'schedule',
    route: '/journee',
    queryParams: { date: creneau.date },
  };
}

/**
 * What the palette shows for a query: destinations first — the palette is
 * primarily a navigator — then the referential entries it matches.
 *
 * <p>An empty query lists the menu's entries only, in the menu's order:
 * proposing 24 animateurs, or the four legal pages, to someone who has just
 * pressed Ctrl+K and typed nothing says nothing about what they can do here.
 * The tabs, the screens listed in no group and the legal pages answer a
 * query.</p>
 */
export function chercherCommandes(query: string, sources: SourcesPalette): CommandePalette[] {
  if (!query.trim()) {
    return sources.destinations.filter((destination) => destination.menu);
  }
  const destinations = sources.destinations.filter((destination) =>
    correspondAuFiltre(query, [
      destination.label,
      destination.hint,
      destination.raccourci,
      destination.motsCles,
    ]),
  );
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
