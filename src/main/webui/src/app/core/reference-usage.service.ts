// What a deletion would take with it, asked to the server and turned into the
// sentence the confirmation dialog appends to its message.
//
// The count comes from the backend and not from the store on purpose: the
// Stands, Animateurs and Créneaux pages never load the persisted planning, so
// they have nothing to count assignments against — and loading a whole plan
// just to count rows would be both expensive and stale the moment a solve
// lands.

import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import { ReferenceUsage } from './models';

/**
 * The referentials the backend can count usages for (`GET /api/<resource>/usages`).
 * A resource absent from this list simply gets no extra sentence — typologies
 * build theirs from the store, since what references them is data the page
 * already holds.
 */
const RESOURCES_COMPTEES: readonly string[] = ['stands', 'animateurs', 'creneaux'];

/**
 * Ids per request. The whole selection travels in the query string, and a
 * "select all" on a full timeslot grid would otherwise push the request line
 * past what the server accepts — which would silently drop the count exactly
 * when the deletion is largest. Split rather than truncate: the totals stay
 * exact.
 */
const IDS_PAR_REQUETE = 100;

const AUCUN: ReferenceUsage = { affectations: 0, contraintesAdHoc: 0, verrouillages: 0 };

@Injectable({ providedIn: 'root' })
export class ReferenceUsageService {
  private readonly api = inject(ApiService);

  /**
   * One request for the whole selection — one more per 100 ids beyond that,
   * never one per row: the confirmation of a bulk delete shows a single total.
   *
   * Returns an empty string — no sentence at all — when the resource has no
   * counter or when the call fails. **The count informs, it never blocks**: a
   * server that cannot answer must not stand between the user and a deletion
   * they asked for.
   */
  async describe(resource: string, ids: readonly (string | number)[]): Promise<string> {
    if (!RESOURCES_COMPTEES.includes(resource) || ids.length === 0) {
      return '';
    }
    try {
      const lots = await Promise.all(
        decouper(ids).map((lot) => this.api.get<ReferenceUsage>(url(resource, lot)))
      );
      return phraseUsages(lots.reduce(additionner, AUCUN));
    } catch {
      return '';
    }
  }
}

function decouper(ids: readonly (string | number)[]): (string | number)[][] {
  const lots: (string | number)[][] = [];
  for (let debut = 0; debut < ids.length; debut += IDS_PAR_REQUETE) {
    lots.push(ids.slice(debut, debut + IDS_PAR_REQUETE));
  }
  return lots;
}

function url(resource: string, ids: readonly (string | number)[]): string {
  const query = ids.map((id) => `id=${encodeURIComponent(String(id))}`).join('&');
  return `/api/${resource}/usages?${query}`;
}

/**
 * Sums the counters of two batches. Each server-side counter already
 * de-duplicates within its own batch; an ad hoc constraint naming two selected
 * animateurs split across two batches would be counted twice — a one-off
 * overstatement, in a figure that exists to give an order of magnitude.
 */
function additionner(cumul: ReferenceUsage, lot: ReferenceUsage): ReferenceUsage {
  return {
    affectations: cumul.affectations + lot.affectations,
    contraintesAdHoc: cumul.contraintesAdHoc + lot.contraintesAdHoc,
    verrouillages: cumul.verrouillages + lot.verrouillages
  };
}

/**
 * The counters as one sentence. Zero is said rather than omitted: "nothing
 * references it" is the answer that lets someone delete without hesitating,
 * and silence would read as "the count failed".
 */
export function phraseUsages(usages: ReferenceUsage): string {
  const total = usages.affectations + usages.contraintesAdHoc + usages.verrouillages;
  if (total === 0) {
    return $localize`:@@usages.none:Rien dans le planning n'y fait référence.`;
  }
  return $localize`:@@usages.counts:Référencé par ${usages.affectations}:affectations: affectation(s), ${usages.contraintesAdHoc}:contraintes: ajustement(s) manuel(s) et ${usages.verrouillages}:verrouillages: verrouillage(s).`;
}
