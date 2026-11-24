// What a session opens — shared by the login page and the interface that reads it.

import { StatutSession } from './models';

/** The role that opens everything, the instance's settings included. */
export const ROLE_ADMIN = 'admin';

/** The role that opens the editions its rights name, and nothing of the instance. */
export const ROLE_ORGANISATEUR = 'organisateur';

/** True when someone is signed in but no role of the application opens anything for them. */
export function signedInWithoutAccess(statut: StatutSession): boolean {
  return (
    statut.authentifie &&
    !statut.roles.includes(ROLE_ADMIN) &&
    !statut.roles.includes(ROLE_ORGANISATEUR)
  );
}
