// View state (sort, quick filter, selected view) held in the URL rather than in
// browser storage or a per-user server record: a refresh (F5) restores the exact
// screen, and the same link pasted to a colleague opens the same one. See
// `docs/decisions/0012-etat-de-vue-dans-l-url.md`.
//
// The month calendar and the animateur timeline seeded their own query params
// by hand before this file existed; this is that pattern, factored out at the
// third page rather than copied a third time.

import { Location } from '@angular/common';
import { effect, inject } from '@angular/core';
import { convertToParamMap, ParamMap, Params } from '@angular/router';

/**
 * Same shape as Angular Material's `Sort`, and assignable both ways — spelled
 * out here so `core/` keeps depending on no UI kit.
 */
export interface SortState {
  active: string;
  direction: 'asc' | 'desc' | '';
}

/** The default of every sortable table: rows in the order the source gave them. */
export const NO_SORT: SortState = { active: '', direction: '' };

/**
 * Reads `?sort=column&dir=asc`, falling back to {@link NO_SORT} on anything
 * else — a hand-edited direction, a `sort` without its `dir`, a column that no
 * longer exists. The column name is deliberately *not* checked against the
 * table: the columns of a report are only known once it has loaded, and a
 * comparator asked for an unknown one already answers "equal", which leaves the
 * rows in source order. An obsolete link therefore degrades to an unsorted
 * table instead of an error.
 */
export function readSort(params: ParamMap): SortState {
  const active = params.get('sort');
  const direction = params.get('dir');
  if (!active || (direction !== 'asc' && direction !== 'desc')) {
    return NO_SORT;
  }
  return { active, direction };
}

/** The inverse of {@link readSort}: an unsorted table clears both params. */
export function sortQueryParams(sort: SortState): Params {
  const sorted = Boolean(sort.active) && sort.direction !== '';
  return { sort: sorted ? sort.active : null, dir: sorted ? sort.direction : null };
}

/**
 * `null` for an empty value, never `''`: a param set to the empty string stays
 * in the URL as a trailing `?q=`, which then looks like state to restore.
 */
export function optionalParam(value: string | null | undefined): string | null {
  return value?.trim() ? value : null;
}

/**
 * Mirrors the page's view state into the URL, and keeps mirroring it on every
 * change. Call from an injection context (a field initializer or the
 * constructor).
 *
 * The current history entry is *replaced*, never pushed: browsing a table
 * sorts, filters and re-sorts it, and each of those would otherwise be an entry
 * the Back button has to walk through before leaving the page.
 *
 * **The address bar is written directly, without a router navigation**, and
 * that is the whole point of this function rather than an implementation
 * detail. It used to call `router.navigate(..., {replaceUrl: true})`, which
 * runs a full navigation cycle on every change of the state it mirrors — so
 * every single keystroke in a quick-filter field re-rendered the page and the
 * field lost the focus, forcing a click before the next character. The URL is a
 * *reflection* of the screen here, not a request to go somewhere: nothing has
 * to be resolved, activated or re-rendered when it changes.
 *
 * Reading it back is unaffected, and deliberately so: every page using this
 * reads `route.snapshot.queryParamMap` once, when it is constructed. A reload,
 * a shared link and a Back that returns to the page all go through a real
 * navigation, which parses the address bar the router state was momentarily out
 * of step with — so both usages the URL exists for keep working. A page that
 * ever needs to *observe* its query params has to make this a navigation again,
 * and pay the re-render. See `docs/decisions/0018-ecrire-l-url-de-vue-sans-naviguer.md`.
 *
 * **A writer owns the keys it names, and only those.** A page made of several
 * components — the Journée and its four views, the Diagnostic and its tabs —
 * has several writers on one URL: the container mirrors the day and the tab,
 * each view its own cursor or filter. Every write therefore starts from the
 * query string as it stands and replaces its own keys, so two writers never
 * erase each other. A key nobody names any more (a bookmark from an older
 * version) simply stays: reading is tolerant, and a stale param harms nothing.
 */
export function keepViewInQueryParams(queryParams: () => Params): void {
  const location = inject(Location);
  effect(() => {
    const [chemin, courante = ''] = location.path().split('?');
    const query = joined(foreignPieces(courante, queryParams()), queryString(queryParams()));
    // `'/'` rather than `''` for a bare root: the History API reads an empty
    // URL as "the current one", query string included, and the key being
    // cleared would then survive its own clearing.
    location.replaceState(query ? chemin + '?' + query : chemin || '/');
  });
}

/**
 * The query params **as the address bar has them now**, for a component that
 * restores its view state when it is created.
 *
 * Not `route.snapshot.queryParamMap`, and that is the whole point: this file
 * writes the URL with `Location.replaceState`, without a router navigation
 * (ADR 0018), so the router's snapshot keeps whatever the last real navigation
 * parsed. That is invisible to a routed page — it is created by a navigation —
 * and wrong for a component the page creates and destroys as the user moves
 * between tabs: the second time a tab was opened it read the state of the
 * first navigation, restored its defaults, and its own effect then wrote those
 * defaults over the address bar. Typing a name into the fragility tab, leaving
 * it and coming back lost both the name and the `q=` in the URL.
 *
 * Reading the address bar is symmetric with writing it, and works in both
 * cases: a real navigation updates `Location` too.
 */
export function currentViewParams(): ParamMap {
  const location = inject(Location, { optional: true });
  const [, query = ''] = (location?.path() ?? '').split('?');
  const params: Params = {};
  for (const morceau of query.split('&')) {
    if (!morceau) {
      continue;
    }
    const egal = morceau.indexOf('=');
    const key = decode(egal === -1 ? morceau : morceau.slice(0, egal));
    const valeur = egal === -1 ? '' : decode(morceau.slice(egal + 1));
    const existante = params[key];
    params[key] = existante === undefined ? valeur : [existante, valeur].flat();
  }
  return convertToParamMap(params);
}

/**
 * The current query string with `params` written over it: every key named by
 * `params` replaced, the others kept **verbatim**.
 *
 * A key nobody names any more is carried across as the raw piece it was, never
 * decoded and re-encoded: that round trip rewrote a stranger's address for no
 * reason (`autre=a+b` came back `autre=a%2Bb`), dropped a flag with no value at
 * all (`?drapeau`), and threw on a malformed escape — `?q2=100%` is an address
 * the router accepts, so the effect mirroring the URL died on its first run and
 * stayed dead for the life of the page.
 *
 * `Object.hasOwn` rather than `key in`: a param called `constructor` or
 * `toString` inherits from `Object.prototype` and was silently dropped.
 */
function foreignPieces(courante: string, params: Params): string[] {
  const pieces: string[] = [];
  for (const morceau of courante.split('&')) {
    if (!morceau) {
      continue;
    }
    const egal = morceau.indexOf('=');
    const key = decode(egal === -1 ? morceau : morceau.slice(0, egal));
    if (!Object.hasOwn(params, key)) {
      pieces.push(morceau);
    }
  }
  return pieces;
}

/** The foreign pieces and the ones this writer owns, in one query string. */
function joined(etrangers: string[], propres: string): string {
  return [...etrangers, propres].filter((morceau) => morceau !== '').join('&');
}

/** `decodeURIComponent` that answers the raw text on a malformed escape rather than throwing. */
function decode(brut: string): string {
  try {
    return decodeURIComponent(brut);
  } catch {
    return brut;
  }
}

/**
 * The params as Angular's own serializer would write them: an entry left at its
 * default is dropped (`null`, `undefined`, `''` — see `optionalParam`), the
 * rest is percent-encoded.
 *
 * Deliberately not `URLSearchParams`, which writes a space as `+`. Angular
 * decodes a query value with `decodeURIComponent`, which leaves a `+` as a
 * literal plus — a filter on two words would come back mangled from a reload,
 * which is the one thing this whole file exists to make work.
 */
function queryString(params: Params): string {
  const morceaux: string[] = [];
  for (const [key, valeur] of Object.entries(params)) {
    for (const unique of Array.isArray(valeur) ? valeur : [valeur]) {
      if (unique !== null && unique !== undefined && unique !== '') {
        morceaux.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(unique)));
      }
    }
  }
  return morceaux.join('&');
}
