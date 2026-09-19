// The public links of the espace animateur — the URL token is the credential,
// so these are addresses a page shows, not calls it makes.

/** Which layout of the individual PDF is asked for — see the backend's FormatPlanning. */
export type FormatPlanning = 'livret' | 'feuille';

/**
 * The animateur's planning as a PDF, downloadable without a session. The
 * booklet is the default; the folded sheet is the same content on one
 * landscape page, printed on both sides.
 */
export function espacePlanningPdfUrl(jeton: string, format: FormatPlanning = 'livret'): string {
  const base = `/api/espace-animateur/${jeton}/planning.pdf`;
  return format === 'livret' ? base : `${base}?format=${format}`;
}

/** The same planning as a one-off calendar file. */
export function espacePlanningIcsUrl(jeton: string): string {
  return `/api/espace-animateur/${jeton}/planning.ics`;
}

/**
 * The calendar subscription, absolute because a calendar client has no page
 * to resolve a relative path against. A token of its own, and a path that
 * opens the calendar and nothing else.
 */
export function abonnementIcsUrl(origin: string, token: string): string {
  return `${origin}/api/abonnements/${token}/planning.ics`;
}
