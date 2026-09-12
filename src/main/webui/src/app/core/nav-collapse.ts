// Which navigation groups the user has folded away, kept in localStorage so
// the drawer looks the same on the next visit. Stored by stable group id, not
// by title: the titles are translated, and a language switch must not silently
// unfold everything.

const STORAGE_KEY = 'planning-equipes.nav.collapsedGroups';

/**
 * Reads the folded groups. Anything unreadable (no storage, invalid JSON, a
 * value written by an older version) yields an empty set: every group open is
 * the harmless default.
 *
 * `known`, when given, keeps only the ids the drawer still has. A release that
 * renames or removes a group leaves its id behind in the browser, and a stale
 * id is not inert: « tout déplier » compares the stored size to the number of
 * groups, so two ghosts are enough to make a drawer with two folded groups
 * claim that everything is folded.
 */
export function readCollapsedGroups(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  known?: Iterable<string>,
): Set<string> {
  if (!storage) {
    return new Set();
  }
  try {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) {
      return new Set();
    }
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return new Set();
    }
    const ids = parsed.filter((id): id is string => typeof id === 'string');
    if (known === undefined) {
      return new Set(ids);
    }
    const allowed = new Set(known);
    return new Set(ids.filter((id) => allowed.has(id)));
  } catch {
    return new Set();
  }
}

/** Writes the folded groups, ignoring a storage that refuses to be written to (private mode, quota). */
export function writeCollapsedGroups(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  collapsed: ReadonlySet<string>,
): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify([...collapsed]));
  } catch {
    // Nothing to do: the drawer still works, it just forgets on reload.
  }
}

/** Returns a new set with `id` folded or unfolded, leaving the input untouched. */
export function toggleCollapsedGroup(collapsed: ReadonlySet<string>, id: string): Set<string> {
  const next = new Set(collapsed);
  if (!next.delete(id)) {
    next.add(id);
  }
  return next;
}

/** The browser's localStorage when it is reachable — `null` under SSR or a hardened browser. */
export function defaultNavStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
