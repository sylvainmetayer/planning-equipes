// Picks the edition this browser works in before the admin shell loads, when
// nothing was ever picked. The server never chooses for a request that names
// no edition (ADR 0072): it refuses it, so the choice has to be made here,
// once, before any screen fires a request.

import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { EditionsApi } from './api/editions-api';
import { Edition } from './models';
import { getStoredEditionId, setStoredEditionId } from './edition-courante';

/**
 * The edition a browser that never chose lands in: the active one — the
 * event under way — or, between two events, the most recently created one.
 * `null` only for an empty list, which the server never answers (the last
 * edition cannot be deleted).
 */
export function firstEditionChoice(editions: readonly Edition[]): Edition | null {
  return editions.find((edition) => edition.active) ?? editions.at(-1) ?? null;
}

/**
 * Lets the admin shell load once an edition is stored. A stored choice is
 * trusted as is: if it names an edition someone deleted, the server answers
 * `EDITION_INCONNUE` and `editionInterceptor` starts over from here. A failed
 * listing (a 401 on an expired session) lets the navigation go on — the
 * auth interceptor is already on its way to /login.
 */
export const editionChosenGuard: CanActivateFn = () =>
  chooseEditionIfNone(inject(EditionsApi)).then(() => true);

/** Stores a first choice when the browser holds none; never fails. */
async function chooseEditionIfNone(editionsApi: EditionsApi): Promise<void> {
  if (getStoredEditionId()) {
    return;
  }
  try {
    const choice = firstEditionChoice(await editionsApi.list());
    if (choice) {
      setStoredEditionId(choice.id);
    }
  } catch {
    // Reported by the interceptors; nothing to choose from.
  }
}
