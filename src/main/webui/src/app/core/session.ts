// What a session opens — shared by the login page and the interface that reads it.

import { StatutSession } from './models';

/** The role that opens the administration; `/api/*` answers 403 without it. */
export const ROLE_ADMIN = 'admin';

/** True when someone is signed in but the administration is closed to them. */
export function signedInWithoutAdminRole(statut: StatutSession): boolean {
  return statut.authentifie && !statut.roles.includes(ROLE_ADMIN);
}
