// A responsable de stand has one screen: « Mon stand ». The admin shell would
// answer every one of its calls with a 403, so it never loads for them.

import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { responsableOnly } from './session';
import { SessionStore } from './session.store';

/** Sends a person whose only right is a stand's to `/responsable`; lets everybody else through. */
export const responsableGuard: CanActivateFn = async () => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.ready();
  const statut = session.statut();
  return statut !== null && responsableOnly(statut) ? router.parseUrl('/responsable') : true;
};
