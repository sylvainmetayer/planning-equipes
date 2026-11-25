// What a session opens — shared by the login page and the interface that reads it.

import { StatutSession } from './models';

/** The role that opens everything, the instance's settings included. */
export const ROLE_ADMIN = 'admin';

/** The role that opens the editions its rights name, and nothing of the instance. */
export const ROLE_ORGANISATEUR = 'organisateur';

/** The role that reads the published shifts of its own stands, and nothing else. */
export const ROLE_RESPONSABLE = 'responsable_stand';

/** True when someone is signed in but no role of the application opens anything for them. */
export function signedInWithoutAccess(statut: StatutSession): boolean {
  return (
    statut.authentifie &&
    !statut.roles.includes(ROLE_ADMIN) &&
    !statut.roles.includes(ROLE_ORGANISATEUR) &&
    !statut.roles.includes(ROLE_RESPONSABLE)
  );
}

/** True for a person whose only door is « Mon stand »: none of the administration's. */
export function responsableOnly(statut: StatutSession): boolean {
  return (
    statut.authentifie &&
    statut.roles.includes(ROLE_RESPONSABLE) &&
    !statut.roles.includes(ROLE_ADMIN) &&
    !statut.roles.includes(ROLE_ORGANISATEUR)
  );
}
