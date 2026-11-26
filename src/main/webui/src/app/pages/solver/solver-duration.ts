// Saying the solve budget, kept out of the page so it is unit tested without
// rendering. The API gives seconds; the operator thinks in minutes or hours.

/** « 2 h », « 15 min », « 90 s » — a budget said in the unit a person would say it in. */
export function formatSeconds(seconds: number): string {
  if (seconds !== 0 && seconds % 3600 === 0) {
    return `${seconds / 3600} h`;
  }
  if (seconds !== 0 && seconds % 60 === 0) {
    return `${seconds / 60} min`;
  }
  return `${seconds} s`;
}
