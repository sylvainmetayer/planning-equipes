// Which groupe (edition — "Année 2025", "Année 2026") this browser is working
// in. Stored client-side and sent on every request as `X-Groupe-Id`, rather
// than flagged server-side: two tabs can then sit on two different editions at
// once, which a global "active group" flag would make impossible. See
// docs/groupes.md §5.
//
// Deliberately a plain module, not an injectable: the HTTP interceptor reads it
// on every request, and going through a service would make it depend on the
// very HttpClient it is intercepting.

const STORAGE_KEY = 'planning-equipes.groupeId';

/** `null` when nothing was ever picked — the server then falls back to its default group. */
export function getStoredGroupeId(): string | null {
  return localStorage.getItem(STORAGE_KEY);
}

/**
 * Switches edition and reloads the page. A reload rather than a store refresh:
 * it swaps the data behind *every* open screen at once, and is the only
 * guarantee that no page keeps rendering the previous group's rows — same
 * reasoning as the language toggle. It is a rare, deliberate action.
 */
export function setStoredGroupeIdAndReload(groupeId: string): void {
  localStorage.setItem(STORAGE_KEY, groupeId);
  location.reload();
}

/** Forgets the stored choice, e.g. after the server reported that group as unknown. */
export function clearStoredGroupeId(): void {
  localStorage.removeItem(STORAGE_KEY);
}

/** Per-group key for anything else this browser persists (notifications log, ...). */
export function groupeScopedKey(base: string): string {
  const groupeId = getStoredGroupeId();
  return groupeId ? `${base}.${groupeId}` : base;
}
