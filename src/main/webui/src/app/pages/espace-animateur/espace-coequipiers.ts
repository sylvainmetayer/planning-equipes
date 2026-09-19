// « Suis-je avec quelqu'un ? » (issue #615).
//
// The teammates were written seat by seat, so answering that question meant
// reading the whole planning and remembering. Pure functions, over the days
// the page already builds: this ranks nobody and reveals nobody — every name
// here is one the animateur already reads on one of their own cards
// (`docs/securite.md`, perimeter of the espace).

import { correspondAuFiltre } from '../../core/text-filter';
import { JourPlanning } from './espace-maintenant';

/**
 * Above this many teammates on one seat, the stand is a crowd rather than a
 * team: the montage and the démontage of an event put most of the roster on
 * one stand at once. Listing a hundred names answers nothing — and it would
 * drown the four people somebody actually spends their fortnight with under a
 * hundred entries seen once each. Such a seat is summarised by its headcount.
 */
export const EQUIPE_NOMBREUSE = 20;

/** One shift shared with somebody. */
export interface OccurrenceCoequipier {
  date: string;
  heureDebut: string;
  heureFin: string;
  standNom: string;
}

/** Somebody met on at least one shift, and where. */
export interface Coequipier {
  nom: string;
  occurrences: OccurrenceCoequipier[];
}

/** A seat held with most of the roster: said as a headcount, never as a list. */
export interface AffluenceStand {
  date: string;
  standNom: string;
  heureDebut: string;
  heureFin: string;
  /** The animateur and their teammates: what « 104 personnes présentes » counts. */
  effectif: number;
}

export interface CoequipiersView {
  /** The people met, the most frequent first, then by name. */
  coequipiers: Coequipier[];
  /** The crowds, chronologically — montage, démontage, an opening ceremony. */
  affluences: AffluenceStand[];
}

/**
 * Who this planning puts somebody next to, and how often.
 *
 * <p>Sorted by number of shared shifts, then by name: the person one works
 * four days with comes before the one met once, and two people met as often
 * are in an order that does not move between two readings.</p>
 */
export function coequipiersView(jours: readonly JourPlanning[]): CoequipiersView {
  const byNom = new Map<string, OccurrenceCoequipier[]>();
  const affluences: AffluenceStand[] = [];
  for (const jour of jours) {
    for (const poste of jour.postes) {
      const occurrence: OccurrenceCoequipier = {
        date: poste.date ?? jour.date,
        heureDebut: (poste.heureDebut ?? '').slice(0, 5),
        heureFin: (poste.heureFin ?? '').slice(0, 5),
        standNom: poste.standNom,
      };
      if (poste.coequipiers.length > EQUIPE_NOMBREUSE) {
        affluences.push({ ...occurrence, effectif: poste.coequipiers.length + 1 });
        continue;
      }
      for (const nom of poste.coequipiers) {
        const occurrences = byNom.get(nom);
        if (occurrences) {
          occurrences.push(occurrence);
        } else {
          byNom.set(nom, [occurrence]);
        }
      }
    }
  }
  const coequipiers = [...byNom.entries()]
    .map(([nom, occurrences]) => ({ nom, occurrences }))
    .sort(
      (left, right) =>
        right.occurrences.length - left.occurrences.length || left.nom.localeCompare(right.nom),
    );
  return { coequipiers, affluences };
}

/**
 * The people whose name matches what was typed — accents and case ignored, as
 * everywhere else a name is looked up in this application. An empty search
 * matches everybody, so the tab filters unconditionally instead of branching.
 */
export function filterCoequipiers(
  coequipiers: readonly Coequipier[],
  recherche: string,
): Coequipier[] {
  return coequipiers.filter((coequipier) => correspondAuFiltre(recherche, [coequipier.nom]));
}
