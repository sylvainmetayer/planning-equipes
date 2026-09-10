// The public links of the espace animateur — the URL token is the credential,
// so these are addresses a page shows, not calls it makes.

/** The animateur's planning as a PDF, downloadable without a session. */
export function espacePlanningPdfUrl(jeton: string): string {
  return `/api/espace-animateur/${jeton}/planning.pdf`;
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
