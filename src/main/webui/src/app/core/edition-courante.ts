// Which edition ("Année 2025", "Année 2026") this browser is working
// in. Stored client-side and sent on every request as `X-Edition-Id`, rather
// than flagged server-side: two tabs can then sit on two different editions at
// once. See docs/decisions/0001-cloisonnement-par-edition.md §5. The server
// never picks one for a request that names none (ADR 0072): the choice is
// made here, once, by `editionChosenGuard`, before the admin shell loads.
//
// Deliberately a plain module, not an injectable: the HTTP interceptor reads it
// on every request, and going through a service would make it depend on the
// very HttpClient it is intercepting.

const STORAGE_KEY = 'planning-equipes.editionId';

/**
 * The choice kept in memory too: a browser whose storage is blocked still
 * names its edition on every request for as long as the page lives — the
 * server refuses a request that names none (ADR 0072).
 */
let inMemoryEditionId: string | null = null;

/** `null` when nothing was ever picked — `editionChosenGuard` then picks one before any request. */
export function getStoredEditionId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? inMemoryEditionId;
  } catch {
    return inMemoryEditionId;
  }
}

/** Records the choice without reloading: for a first pick, made before any screen has loaded. */
export function setStoredEditionId(editionId: string): void {
  inMemoryEditionId = editionId;
  try {
    localStorage.setItem(STORAGE_KEY, editionId);
  } catch {
    // Blocked storage: the in-memory choice carries this page.
  }
}

/**
 * Switches edition and reloads the page. A reload rather than a store refresh:
 * it swaps the data behind *every* open screen at once, and is the only
 * guarantee that no page keeps rendering the previous edition's rows — same
 * reasoning as the language toggle. It is a rare, deliberate action.
 */
export function setStoredEditionIdAndReload(editionId: string): void {
  localStorage.setItem(STORAGE_KEY, editionId);
  location.reload();
}

/**
 * Switches edition and opens `url` in it — « ouvrir dans l'édition B » of
 * the comparison of two editions. A full load rather than a router
 * navigation, for the reason {@link setStoredEditionIdAndReload} gives: no
 * screen may keep rendering the previous edition's rows.
 */
export function setStoredEditionIdAndOpen(editionId: string, url: string): void {
  localStorage.setItem(STORAGE_KEY, editionId);
  location.assign(url);
}

/** Forgets the stored choice, e.g. after the server reported that edition as unknown. */
export function clearStoredEditionId(): void {
  inMemoryEditionId = null;
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Blocked storage: nothing was stored there.
  }
}

/** Per-edition key for anything else this browser persists (notifications log, ...). */
export function editionScopedKey(base: string): string {
  const editionId = getStoredEditionId();
  return editionId ? `${base}.${editionId}` : base;
}
