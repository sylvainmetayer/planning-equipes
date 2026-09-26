// How a view counts the seats of a plan once a seat was split on the day
// (ADR 0066): two rows, one place. The server's `SeatPlaces` reads the same
// rule, so a count on screen and the same count in a report agree.
//
// A seat split at « now » becomes its origin — cut short, kept by whoever held
// it — and a continuation naming it (`suiteDe`). A view that counts places
// (seats, staffed seats) counts the place once, on its current part: the one
// no other seat continues. A view that sums hours sums every part on its own
// window. A narrowed seat names no origin and nobody continues it: one place,
// on its narrowed window.

import { PosteAffectation } from './models';
import { endMinutesOfDay, minutesOfDay } from './time-of-day';

/** The ids of the seats another seat of `postes` continues: the parts that are no longer current. */
export function continuedSeatIds(postes: readonly PosteAffectation[]): Set<string> {
  const ids = new Set<string>();
  for (const poste of postes) {
    if (poste.suiteDe) {
      ids.add(poste.suiteDe);
    }
  }
  return ids;
}

/**
 * The seats counted as places: every seat but those a later part continues.
 * Give it the whole plan, or a slice that keeps a stand × timeslot whole — a
 * day, a stand: the two parts of a seat always share both.
 */
export function placesOf(postes: readonly PosteAffectation[]): PosteAffectation[] {
  const continued = continuedSeatIds(postes);
  return continued.size === 0 ? [...postes] : postes.filter((poste) => !continued.has(poste.id));
}

/**
 * Minutes a seat covers: the server's figure when it sent one — it reads a
 * window past midnight right —, else its effective window, else its
 * timeslot's.
 */
export function seatMinutes(poste: PosteAffectation): number {
  if (typeof poste.dureeEffectiveMinutes === 'number') {
    return poste.dureeEffectiveMinutes;
  }
  const creneau = poste.creneau;
  if (!creneau) {
    return 0;
  }
  const debut = minutesOfDay(poste.heureDebutEffective ?? creneau.heureDebut);
  const fin = endMinutesOfDay(poste.heureFinEffective ?? creneau.heureFin);
  return Math.max(fin - debut, 0);
}
