// The public addresses of the SPA a page hands out — printed, copied, shown
// as a QR code — the frontend side of the backend's ApplicationLinks: one
// function per link, so renaming a route means renaming it here.

/** The animateur's espace, the link the individual PDF prints. */
export function espaceUrl(origin: string, token: string): string {
  return `${origin}/animateur/${encodeURIComponent(token)}`;
}

/** The control room's wall display a link token opens. */
export function muralUrl(origin: string, token: string): string {
  return `${origin}/mural/${encodeURIComponent(token)}`;
}
