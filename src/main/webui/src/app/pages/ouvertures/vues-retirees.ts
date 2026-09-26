// Two views of the openings page were folded into its grid: « Journée » (one
// day on the time axis) and « Calendrier combiné » (the layers over a week).
// Their addresses still work — a bookmark, a link of the help, a « Que
// faire ? » action — and land where the same question is now answered, with
// their query params carried along. Pure, so the mapping is tested without a
// router; `app.routes.ts` wires it as a guard of `/ouvertures`.

import { inject } from '@angular/core';
import { CanActivateFn, Params, Router } from '@angular/router';
import { PlanningStateService } from '../../core/planning-state.service';

/** A query param as `String()` would print it: a repeated param is joined by commas. */
function text(value: unknown): string {
  return Array.isArray(value) ? value.join(',') : String(value);
}

function query(params: Record<string, string>): string {
  const encoded = new URLSearchParams(params).toString().replaceAll('+', '%20');
  return encoded ? `?${encoded}` : '';
}

/**
 * Where a retired view's address now leads, or `null` when it names none.
 *
 * - `?vue=journee&date=D`: the day's planning (`/journee?date=D`) once a plan
 *   is computed — the seats are then people, which is what the day view is
 *   for; before that, the grid narrowed to that day (`?du=D&au=D`).
 * - `?vue=calendrier`: the grid, whose cells now draw the layers; `du` and
 *   `couches` mean there what they meant in the calendar.
 */
export function retiredViewTarget(params: Params, planComputed: boolean): string | null {
  const view = params['vue'];
  if (view !== 'journee' && view !== 'calendrier') {
    return null;
  }
  const kept: Record<string, string> = {};
  for (const [key, value] of Object.entries(params)) {
    if (key !== 'vue' && value !== undefined && value !== null) {
      kept[key] = text(value);
    }
  }
  if (view === 'calendrier') {
    return `/ouvertures${query(kept)}`;
  }
  const date = kept['date'];
  if (planComputed) {
    // The day page keeps the stand search and the exact stand under the same names.
    const journee: Record<string, string> = {};
    for (const key of ['date', 'q', 'stand']) {
      if (kept[key]) {
        journee[key] = kept[key];
      }
    }
    return `/journee${query(journee)}`;
  }
  delete kept['date'];
  if (date) {
    kept['du'] = date;
    kept['au'] = date;
  }
  return `/ouvertures${query(kept)}`;
}

/** Whether the edition has a plan whose seats name somebody. */
async function planComputed(): Promise<boolean> {
  try {
    const planning = await inject(PlanningStateService).loadForDisplay();
    return (planning?.postes ?? []).some((poste) => Boolean(poste.animateur));
  } catch {
    return false;
  }
}

/** Guard of `/ouvertures`: a retired view's address is redirected, anything else goes through. */
export const retiredOpeningsViews: CanActivateFn = (route) => {
  const view = route.queryParamMap.get('vue');
  if (view !== 'journee' && view !== 'calendrier') {
    return true;
  }
  const router = inject(Router);
  const plan = view === 'journee' ? planComputed() : Promise.resolve(false);
  return plan.then((computed) => {
    const target = retiredViewTarget(route.queryParams, computed);
    return target === null ? true : router.parseUrl(target);
  });
};
