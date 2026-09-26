// The days an animateur is away, entered the way people say them: a click on
// a day of the edition, or a range — « absent du 8 au 12 » in one gesture
// rather than five. Pure, so the frieze and the range are tested without a
// form. Availability stays opt-out: only the days listed are away.

/** The edition's days, from its timeslots: the dates a solve can place somebody on, in order. */
export function joursEdition(creneaux: readonly { date: string }[]): string[] {
  return [...new Set(creneaux.map((creneau) => creneau.date))].sort((a, b) => a.localeCompare(b));
}

/** One day ticked or unticked; the list stays sorted. */
export function basculerJour(jours: readonly string[], date: string): string[] {
  return jours.includes(date)
    ? jours.filter((jour) => jour !== date)
    : [...jours, date].sort((a, b) => a.localeCompare(b));
}

/** Longest range laid at once when the edition has no day yet to narrow it: a year. */
const PLAGE_MAX_JOURS = 366;

/** Every date from `du` to `au`, both included; empty when either is unreadable or `au` is before `du`. */
export function rangeDates(du: string, au: string): string[] {
  const debut = Date.parse(`${du}T00:00:00Z`);
  const fin = Date.parse(`${au}T00:00:00Z`);
  if (Number.isNaN(debut) || Number.isNaN(fin) || fin < debut) {
    return [];
  }
  const dates: string[] = [];
  for (let jour = debut; jour <= fin && dates.length < PLAGE_MAX_JOURS; jour += 86_400_000) {
    dates.push(new Date(jour).toISOString().slice(0, 10));
  }
  return dates;
}

/**
 * A range marked away: the edition's days inside it — a day off on a day with
 * no timeslot would be written and then erased by the first availability
 * declaration — or every date of it while the edition has no day at all.
 * Answers the list and how many days the range added.
 */
export function addRange(
  jours: readonly string[],
  du: string,
  au: string,
  edition: readonly string[],
): { jours: string[]; ajoutes: number } {
  const plage = rangeDates(du, au);
  const retenues = edition.length === 0 ? plage : plage.filter((date) => edition.includes(date));
  const nouvelles = retenues.filter((date) => !jours.includes(date));
  return {
    jours: [...jours, ...nouvelles].sort((a, b) => a.localeCompare(b)),
    ajoutes: nouvelles.length,
  };
}
