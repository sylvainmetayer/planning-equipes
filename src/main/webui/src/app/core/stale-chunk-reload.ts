import { NavigationError } from '@angular/router';

/**
 * Self-healing for a stale application shell (the mobile "dead menu" bug).
 *
 * Every page of the admin shell is lazy-loaded: clicking a drawer link fetches
 * that page's `chunk-*.js`. Those files are content-hashed, so a redeployment
 * replaces the whole set — and a browser still running the previous shell
 * (an `index.html` cached up to 24h by the server's static-file defaults)
 * asks for chunks that no longer exist. The dynamic import rejects, the
 * router cancels the navigation and restores the URL, and nothing visible
 * happens: every menu item looks dead. The page the user *lands on* works
 * (its code came with the shell), which is why the bug reads as "the menu is
 * broken on the Solveur page" — `/` is the entry everyone has cached, while
 * typing `/debug` in the address bar is a different cache entry, hence a
 * fresh shell, hence a working menu.
 *
 * The recovery is the one thing a stale client can always do: a full-page
 * load of the very URL the user asked for. The server answers any route with
 * the current `index.html` (SPA fallback), whose chunk names all exist, so
 * the user simply arrives where they tapped — one white flash instead of a
 * dead button. It is a `replace`, not an `assign`: the shell being escaped is
 * broken, and leaving it one Back-swipe away would hand it straight back to
 * the user — with the cooldown then refusing to heal it twice.
 *
 * A dropped connection rejects a dynamic import with the very same message as
 * a deleted chunk, so the recovery is skipped while the browser reports
 * itself offline. `navigator.onLine` only ever says *definitely offline* with
 * authority, which is precisely the case worth excluding: reloading there
 * would trade a dead link for the browser's offline page, losing the whole
 * session. The cooldown guards the remaining pathological case where the
 * reload itself still cannot load the chunk (server truly broken): without
 * it, boot → failed initial navigation → reload → boot… would loop forever.
 */

/** Last automatic reload, remembered across the reload itself. */
const STORAGE_KEY = 'planning-equipes.rechargement-shell';

/** One automatic reload per target URL per minute: heals once, never loops. */
const RELOAD_COOLDOWN_MS = 60_000;

/**
 * Whether this navigation failed because a lazily-loaded chunk could not be
 * fetched — each engine words it differently, none exposes a typed error.
 */
export function isChunkLoadFailure(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return (
    /failed to fetch dynamically imported module/i.test(message) || // Chromium
    /error loading dynamically imported module/i.test(message) || // Firefox
    /importing a module script failed/i.test(message) // WebKit
  );
}

/**
 * Whether an automatic reload towards {@code url} is allowed now. Unavailable
 * or broken storage answers no: without a durable marker the cooldown cannot
 * be enforced, and a reload loop is worse than a dead link.
 */
export function shouldReload(storage: Storage | null, url: string, now: number): boolean {
  if (!storage) {
    return false;
  }
  try {
    const raw = storage.getItem(STORAGE_KEY);
    if (raw) {
      const previous = JSON.parse(raw) as { url?: string; at?: number };
      if (
        previous.url === url &&
        typeof previous.at === 'number' &&
        now - previous.at < RELOAD_COOLDOWN_MS
      ) {
        return false;
      }
    }
    storage.setItem(STORAGE_KEY, JSON.stringify({ url, at: now }));
    return true;
  } catch {
    return false;
  }
}

/** `sessionStorage` itself throws when a browser blocks site data entirely. */
function safeSessionStorage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * `withNavigationErrorHandler` hook: turns a chunk-load failure into one full
 * reload of the navigation's target. The extra parameters exist for the
 * tests; the router calls this with the event alone.
 *
 * Being offline is checked before {@link shouldReload}, which writes the
 * cooldown marker: a tunnel must not spend the one reload that a genuine
 * redeployment will need.
 */
export function handleNavigationError(
  event: NavigationError,
  navigate: (url: string) => void = (url) => window.location.replace(url),
  storage: Storage | null = safeSessionStorage(),
  now: number = Date.now(),
  online: boolean = navigator.onLine,
): void {
  if (online && isChunkLoadFailure(event.error) && shouldReload(storage, event.url, now)) {
    navigate(event.url);
  }
}
