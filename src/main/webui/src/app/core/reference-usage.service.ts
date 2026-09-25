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
const RESOURCES_COMPTEES: ReadonlySet<string> = new Set(['stands', 'animateurs', 'creneaux']);

/**
 * Longest query string a batch may build, in encoded characters.
 *
 * The whole selection travels in the URL, and Quarkus refuses a request line
 * longer than 4096 by default — so a "tout sélectionner" would lose the count
 * exactly where the deletion is largest. The budget is on the **accumulated
 * encoded length**, not on a number of ids: stand and animateur ids are free
 * `VARCHAR(64)` text, so a hundred of them can be ten characters or six
 * hundred. Half the server's limit leaves room for the path, the host and the
 * headers of the request line.
 */
const MAX_REQUEST_LENGTH = 2000;

const AUCUN: ReferenceUsage = {
  affectations: 0,
  contraintesAdHoc: 0,
  verrouillages: 0,
  consignes: 0,
};

@Injectable({ providedIn: 'root' })
export class ReferenceUsageService {
  private readonly api = inject(ApiService);

  /**
   * One request for the whole selection — one more each time the query string
   * would outgrow what the server accepts, never one per row: the confirmation
   * of a bulk delete shows a single total.
   *
   * Returns an empty string — no sentence at all — when the resource has no
   * counter or when the call fails. **The count informs, it never blocks**: a
   * server that cannot answer must not stand between the user and a deletion
   * they asked for.
   */
  async describe(resource: string, ids: readonly (string | number)[]): Promise<string> {
    if (!RESOURCES_COMPTEES.has(resource) || ids.length === 0) {
      return '';
    }
    try {
      const lots = await Promise.all(
        split(ids).map((lot) => this.api.get<ReferenceUsage>(`/api/${resource}/usages?${lot}`)),
      );
      return phraseUsages(lots.reduce(additionner, AUCUN));
    } catch {
      return '';
    }
  }
}

/**
 * The selection as query strings, each kept under {@link LONGUEUR_MAX_REQUETE}.
 * A single id longer than the budget still gets its own request rather than
 * being dropped: an over-long URL that the server refuses costs the count, and
 * silently skipping the id would falsify it.
 */
function split(ids: readonly (string | number)[]): string[] {
  const lots: string[] = [];
  let courant = '';
  for (const id of ids) {
    const parametre = `id=${encodeURIComponent(String(id))}`;
    if (courant === '') {
      courant = parametre;
    } else if (courant.length + 1 + parametre.length <= MAX_REQUEST_LENGTH) {
      courant += `&${parametre}`;
    } else {
      lots.push(courant);
      courant = parametre;
    }
  }
  if (courant !== '') {
    lots.push(courant);
  }
  return lots;
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
    verrouillages: cumul.verrouillages + lot.verrouillages,
    consignes: cumul.consignes + lot.consignes,
  };
}

/**
 * The counters as one sentence.
 *
 * Zero is said rather than omitted — "nothing references it" is the answer
 * that lets someone delete without hesitating, and silence would read as "the
 * count failed". But it is said by **naming the four counters**, never as a
 * blanket "nothing refers to it": other tables cascade on these referentials
 * without being counted here (a stand's demandes d'échange, its opening hours,
 * an animateur's competences and wishes), and an absolute sentence would
 * promise something this call never checked. The consignes (issue #4) are
 * named only when one names the row: a person is never under a consigne,
 * and « aucune consigne » under an animateur would read as a check nobody made.
 */
export function phraseUsages(usages: ReferenceUsage): string {
  const total =
    usages.affectations + usages.contraintesAdHoc + usages.verrouillages + usages.consignes;
  if (total === 0) {
    return $localize`:@@usages.none:Aucune affectation, aucun ajustement manuel et aucun verrouillage ne le référencent.`;
  }
  const trois = $localize`:@@usages.counts:Référencé par ${usages.affectations}:affectations: affectation(s), ${usages.contraintesAdHoc}:contraintes: ajustement(s) manuel(s) et ${usages.verrouillages}:verrouillages: verrouillage(s).`;
  if (usages.consignes === 0) {
    return trois;
  }
  const consignes = $localize`:@@usages.consignes:${usages.consignes}:consignes: journée(s) sous consigne l'ouvrent ou l'ont ajouté.`;
  return `${trois} ${consignes}`;
}
