// When this browser last opened a screen that counts what arrived since:
// « 3 nouvelles demandes depuis votre dernière visite » on Échanges.
//
// A chrome preference, on the model of `news-seen`: this browser's, never
// shared, harmless when lost — a lost visit simply counts nothing as new.
// Nothing here may throw: a refused storage (private mode, quota, a blocked
// site) reads as a first visit.

export { defaultNavStorage as defaultVisitStorage } from './nav-collapse';

type VisitStorage = Pick<Storage, 'getItem' | 'setItem'> | null;

const PREFIX = 'planning-equipes.derniere-visite.';

/** The previous visit of `screen`, as an ISO instant, or null on a first visit. */
export function readLastVisit(storage: VisitStorage, screen: string): string | null {
  if (!storage) {
    return null;
  }
  try {
    const value = storage.getItem(PREFIX + screen);
    return value && !Number.isNaN(Date.parse(value)) ? value : null;
  } catch {
    return null;
  }
}

/** Records this visit of `screen`. */
export function writeLastVisit(storage: VisitStorage, screen: string, instant: string): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(PREFIX + screen, instant);
  } catch {
    // Nothing to do: the next visit counts from further back, that is all.
  }
}

/**
 * How many of `instants` came after `since`. A first visit (`since` null)
 * counts nothing as new: everything would be, and a count that says so on
 * every fresh browser teaches people to ignore it.
 */
export function countSince(instants: readonly (string | null)[], since: string | null): number {
  if (!since) {
    return 0;
  }
  const limite = Date.parse(since);
  return instants.filter((instant) => instant !== null && Date.parse(instant) > limite).length;
}
