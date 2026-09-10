// Whether a page panel is folded away, kept in localStorage so the screen looks
// the same on the next visit.
//
// Not in the URL, unlike the view state of `view-query-params.ts` (a sort, a
// filter, a chosen view): those describe what one is looking AT and are worth
// sharing, this describes how much room a panel is allowed on THIS person's
// screen. A query param would also be gone on the next plain navigation, which
// is precisely the visit the preference has to survive.
//
// Same shape and same defensiveness as `nav-collapse`, its closest neighbour —
// which folds the navigation drawer's groups.

import { defaultNavStorage } from './nav-collapse';

/**
 * The browser's localStorage when it is reachable. Borrowed from
 * `nav-collapse` rather than written twice: one place knows that a hardened
 * browser can *throw* on the mere access, not merely return null.
 */
export const defaultPanelStorage = defaultNavStorage;

/**
 * Reads a panel's folded state. Anything unreadable — no storage, a value
 * written by an older version — reads as "unfolded": showing a panel that was
 * meant to be hidden costs some room, hiding one the user expects to see costs
 * them the feature.
 */
export function readPanelCollapsed(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  key: string,
): boolean {
  if (!storage) {
    return false;
  }
  try {
    return storage.getItem(key) === 'true';
  } catch {
    return false;
  }
}

/** Writes it, ignoring a storage that refuses to be written to (private mode, quota). */
export function writePanelCollapsed(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  key: string,
  collapsed: boolean,
): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(key, String(collapsed));
  } catch {
    // Nothing to do: the panel still folds, it just forgets on reload.
  }
}
