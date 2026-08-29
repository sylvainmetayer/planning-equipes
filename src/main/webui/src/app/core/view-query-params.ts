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
import { ParamMap, Params } from '@angular/router';

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
 */
export function keepViewInQueryParams(queryParams: () => Params): void {
  const location = inject(Location);
  effect(() => {
    const chemin = location.path().split('?')[0];
    const query = queryString(queryParams());
    location.replaceState(query ? chemin + '?' + query : chemin);
  });
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
  for (const [cle, valeur] of Object.entries(params)) {
    for (const unique of Array.isArray(valeur) ? valeur : [valeur]) {
      if (unique !== null && unique !== undefined && unique !== '') {
        morceaux.push(encodeURIComponent(cle) + '=' + encodeURIComponent(String(unique)));
      }
    }
  }
  return morceaux.join('&');
}
