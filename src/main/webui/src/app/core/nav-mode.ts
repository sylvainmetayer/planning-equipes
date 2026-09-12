// Which entries the navigation drawer shows: the whole menu, or only the
// screens an organiser needs outside a deep diagnostic. Kept in localStorage
// so the choice looks the same on the next visit.
//
// A chrome preference, on the model of `theme-preference`: neither shareable
// nor worth a URL param, and without any effect on rights — a hidden entry
// stays reachable by its URL, by the command palette and by every link of the
// help. The list of "advanced" screens does not live here either: it is an
// attribute of the navigation entry (`NavLink.avance`), so the menu and the
// filter cannot drift apart.

const STORAGE_KEY = 'planning-equipes.nav.mode';

/**
 * `simple` hides the expert entries, `avance` shows everything. `simple` is
 * the default: most users — the board of an association, not the developer —
 * use half of the forty-odd screens. It hides some fifteen of them: the
 * technical tools, the deep-diagnostic screens, and the specialised renderings
 * of the plan that answer one question each (the day rail, the load heatmap,
 * the rest days…). What is left is the everyday cycle — solve, look at it,
 * fix it, publish it — plus the reference data it runs on.
 */
export type NavMode = 'simple' | 'avance';

/**
 * Reads the stored mode. Anything unreadable — no storage, a value written by
 * an older version — reads as `simple`: the shorter menu is the harmless
 * default, and it is what a first-time visitor gets.
 */
export function readNavMode(storage: Pick<Storage, 'getItem' | 'setItem'> | null): NavMode {
  if (!storage) {
    return 'simple';
  }
  try {
    return storage.getItem(STORAGE_KEY) === 'avance' ? 'avance' : 'simple';
  } catch {
    return 'simple';
  }
}

/** Writes it, ignoring a storage that refuses to be written to (private mode, quota). */
export function writeNavMode(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  mode: NavMode,
): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(STORAGE_KEY, mode);
  } catch {
    // Nothing to do: the menu still switches, it just forgets on reload.
  }
}

/** The other mode: the toolbar control is a two-state toggle. */
export function nextNavMode(mode: NavMode): NavMode {
  return mode === 'simple' ? 'avance' : 'simple';
}
