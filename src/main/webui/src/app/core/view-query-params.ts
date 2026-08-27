// View state (sort, quick filter, selected view) held in the URL rather than in
// browser storage or a per-user server record: a refresh (F5) restores the exact
// screen, and the same link pasted to a colleague opens the same one. See
// `docs/decisions/0012-etat-de-vue-dans-l-url.md`.
//
// The month calendar and the animateur timeline seeded their own query params
// by hand before this file existed; this is that pattern, factored out at the
// third page rather than copied a third time.

import { effect, inject } from '@angular/core';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';

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
 * `replaceUrl` is what makes this bearable to use: browsing a table sorts,
 * filters and re-sorts it, and each of those would otherwise be a history entry
 * the Back button has to walk through before leaving the page.
 */
export function keepViewInQueryParams(queryParams: () => Params): void {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  effect(() => {
    void router.navigate([], { relativeTo: route, queryParams: queryParams(), replaceUrl: true });
  });
}
