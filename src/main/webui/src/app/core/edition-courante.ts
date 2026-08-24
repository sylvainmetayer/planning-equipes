// Which edition ("Année 2025", "Année 2026") this browser is working
// in. Stored client-side and sent on every request as `X-Edition-Id`, rather
// than flagged server-side: two tabs can then sit on two different editions at
// once, which a global "active edition" flag would make impossible. See
// docs/decisions/0001-cloisonnement-par-edition.md §5.
//
// Deliberately a plain module, not an injectable: the HTTP interceptor reads it
// on every request, and going through a service would make it depend on the
// very HttpClient it is intercepting.

const STORAGE_KEY = 'planning-equipes.editionId';

/** `null` when nothing was ever picked — the server then falls back to its default edition. */
export function getStoredEditionId(): string | null {
  return localStorage.getItem(STORAGE_KEY);
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

/** Forgets the stored choice, e.g. after the server reported that edition as unknown. */
export function clearStoredEditionId(): void {
  localStorage.removeItem(STORAGE_KEY);
}

/** Per-edition key for anything else this browser persists (notifications log, ...). */
export function editionScopedKey(base: string): string {
  const editionId = getStoredEditionId();
  return editionId ? `${base}.${editionId}` : base;
}
