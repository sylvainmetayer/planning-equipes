// Time-of-day arithmetic shared by the views that lay vacations out on a time
// axis (the per-animateur timeline, the day rail). Factored out at the second
// use rather than copied: the midnight rule below is a decision, not a detail,
// and two copies of it would drift.

/**
 * `'00:00'` means midnight, i.e. the end of this event day — never the start of
 * the next one (this app's `jour` never spans two calendar dates).
 */
export function endMinutesOfDay(heureFin: string): number {
  const minutes = minutesOfDay(heureFin);
  return minutes === 0 ? 1440 : minutes;
}

/** Minutes since midnight of a `HH:mm` time. */
export function minutesOfDay(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

/**
 * `HH:mm` of a time the API sends as `HH:mm:ss`. Seconds are noise on a
 * schedule, and they cost the width a stand name needs.
 */
export function formatHeure(time: string): string {
  return time.length > 5 ? time.slice(0, 5) : time;
}

/** `HH:mm` label of a whole-hour tick of a time axis. */
export function formatHourTick(hour: number): string {
  return `${String(hour % 24).padStart(2, '0')}:00`;
}

/** Human duration: `45 min`, `3 h`, `3 h 30`. */
export function formatDuration(totalMinutes: number): string {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) {
    return $localize`:@@timeline.duration.minutesOnly:${minutes}:minutes: min`;
  }
  if (minutes === 0) {
    return $localize`:@@timeline.duration.hoursOnly:${hours}:hours: h`;
  }
  return $localize`:@@timeline.duration.hoursAndMinutes:${hours}:hours: h ${minutes}:minutes:`;
}
