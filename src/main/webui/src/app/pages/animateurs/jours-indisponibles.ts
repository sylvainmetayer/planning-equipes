// The days an animateur is away, entered the way people say them: a click on
// a day of the edition, or a range — « absent du 8 au 12 » in one gesture
// rather than five. Pure, so the frieze and the range are tested without a
// form. Availability stays opt-out: only the days listed are away.

/** The edition's days, from its timeslots: the dates a solve can place somebody on, in order. */
export function editionDays(creneaux: readonly { date: string }[]): string[] {
  return [...new Set(creneaux.map((creneau) => creneau.date))].sort((a, b) => a.localeCompare(b));
}

/** One day ticked or unticked; the list stays sorted. */
export function toggleDayOff(days: readonly string[], date: string): string[] {
  return days.includes(date)
    ? days.filter((day) => day !== date)
    : [...days, date].sort((a, b) => a.localeCompare(b));
}

/** Longest range laid at once when the edition has no day yet to narrow it: a year. */
const MAX_RANGE_DAYS = 366;

/** Every date from `from` to `to`, both included; empty when either is unreadable or `to` is before `from`. */
export function rangeDates(from: string, to: string): string[] {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
    return [];
  }
  const dates: string[] = [];
  for (let day = start; day <= end && dates.length < MAX_RANGE_DAYS; day += 86_400_000) {
    dates.push(new Date(day).toISOString().slice(0, 10));
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
  days: readonly string[],
  from: string,
  to: string,
  edition: readonly string[],
): { days: string[]; added: number } {
  const range = rangeDates(from, to);
  const kept = edition.length === 0 ? range : range.filter((date) => edition.includes(date));
  const fresh = kept.filter((date) => !days.includes(date));
  return {
    days: [...days, ...fresh].sort((a, b) => a.localeCompare(b)),
    added: fresh.length,
  };
}
