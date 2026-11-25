// A responsable de stand has one screen: « Mon stand ». The admin shell would
// answer every one of its calls with a 403, so it never loads for them.

import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { responsableOnly, rhOnly } from './session';
import { SessionStore } from './session.store';

/** Sends a person whose only right is a stand's to `/responsable`; lets everybody else through. */
export const responsableGuard: CanActivateFn = async () => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.ready();
  const statut = session.statut();
  if (statut !== null && responsableOnly(statut)) {
    return router.parseUrl('/responsable');
  }
  // An RH-only account would meet a 403 on every call of the shell: its one screen is the report.
  return statut !== null && rhOnly(statut) ? router.parseUrl('/rh') : true;
};
