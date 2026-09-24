// What a session opens, and how to leave it — shared by the admin shell, the
// login page and the espace animateur, the three places a person signs out.

import { AdminApi } from './api/admin-api';
import { StatutSession } from './models';

/** The realm role that opens the administration; `/api/*` answers 403 without it. */
export const ROLE_ADMIN = 'admin';

/** True when the session may use the administration, not merely signed in. */
export function opensAdministration(statut: StatutSession): boolean {
  return statut.authentifie && statut.roles.includes(ROLE_ADMIN);
}

/** True when someone is signed in but the administration is closed to them. */
export function signedInWithoutAdminRole(statut: StatutSession): boolean {
  return statut.authentifie && !statut.roles.includes(ROLE_ADMIN);
}

/**
 * Ends the session, then hard-navigates away.
 *
 * <p>Under Keycloak the server names a second destination — the route ending
 * the identity provider's session (RP-initiated logout, which comes back to
 * `/login`) — and that one is where the browser goes: dropping our own cookie
 * alone would leave Keycloak ready to sign the same account straight back in
 * on the next click, a logout button that logs nobody out. Without one (the
 * break-glass form login) the browser goes to `fallback`.</p>
 *
 * <p>A full navigation rather than a router one: it also resets every store a
 * shell preloaded, so nothing keeps polling behind the next page. It happens
 * even when the server refuses the logout — staying on a screen whose session
 * may already be gone would be worse.</p>
 */
export async function signOut(
  adminApi: Pick<AdminApi, 'logout'>,
  fallback = '/login',
): Promise<void> {
  let destination = fallback;
  try {
    destination = (await adminApi.logout()).urlDeconnexion ?? fallback;
  } finally {
    window.location.assign(destination);
  }
}
