// The self-healing of a stale application shell (the mobile "dead menu" bug):
// a redeployment invalidates every content-hashed chunk, a cached shell keeps
// asking for the old names, and each lazy navigation dies silently. What is
// pinned here is the triage — which failures earn the one full reload — and
// the cooldown that keeps a truly broken server from turning the recovery
// into a reload loop.

import { NavigationError } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { handleNavigationError, isChunkLoadFailure, shouldReload } from './stale-chunk-reload';

/** In-memory Storage double, optionally failing like a blocked browser store. */
function fakeStorage(options: { broken?: boolean } = {}): Storage {
  const data = new Map<string, string>();
  return {
    getItem: (key: string) => {
      if (options.broken) {
        throw new Error('storage disabled');
      }
      return data.get(key) ?? null;
    },
    setItem: (key: string, value: string) => {
      if (options.broken) {
        throw new Error('storage disabled');
      }
      data.set(key, value);
    },
    removeItem: (key: string) => void data.delete(key),
    clear: () => data.clear(),
    key: () => null,
    get length() {
      return data.size;
    },
  };
}

function navigationError(error: unknown, url = '/stands'): NavigationError {
  return new NavigationError(1, url, error);
}

/** The wording of a failed dynamic import, per engine. */
const CHUNK_FAILURES = [
  'Failed to fetch dynamically imported module: https://demo/chunk-FPNODPH2.js',
  'error loading dynamically imported module',
  'Importing a module script failed.',
];

describe('isChunkLoadFailure', () => {
  it.each(CHUNK_FAILURES)('recognizes a failed chunk import: %s', (message) => {
    expect(isChunkLoadFailure(new TypeError(message))).toBe(true);
  });

  // Any other navigation error — a throw in a guard, a bad redirect — is a
  // bug to surface, not a staleness to paper over with a reload.
  it('leaves other navigation errors alone', () => {
    expect(isChunkLoadFailure(new Error('Cannot match any routes'))).toBe(false);
    expect(isChunkLoadFailure('some string')).toBe(false);
    expect(isChunkLoadFailure(undefined)).toBe(false);
  });
});

describe('shouldReload', () => {
  it('allows the first reload towards a target', () => {
    expect(shouldReload(fakeStorage(), '/stands', 1000)).toBe(true);
  });

  // Boot at /stands → the initial navigation fails too → without this, the
  // recovery reload would itself reload, forever.
  it('refuses a second reload towards the same target within the cooldown', () => {
    const storage = fakeStorage();
    expect(shouldReload(storage, '/stands', 1000)).toBe(true);
    expect(shouldReload(storage, '/stands', 31_000)).toBe(false);
  });

  it('allows the same target again once the cooldown has passed', () => {
    const storage = fakeStorage();
    expect(shouldReload(storage, '/stands', 1000)).toBe(true);
    expect(shouldReload(storage, '/stands', 62_000)).toBe(true);
  });

  it('treats another target as a fresh attempt', () => {
    const storage = fakeStorage();
    expect(shouldReload(storage, '/stands', 1000)).toBe(true);
    expect(shouldReload(storage, '/animateurs', 2000)).toBe(true);
  });

  // No durable marker means no enforceable cooldown: a dead link is the
  // lesser evil next to an unbounded reload loop.
  it('refuses when the storage is missing or broken', () => {
    expect(shouldReload(null, '/stands', 1000)).toBe(false);
    expect(shouldReload(fakeStorage({ broken: true }), '/stands', 1000)).toBe(false);
  });

  it('survives a corrupted marker', () => {
    const storage = fakeStorage();
    storage.setItem('planning-equipes.rechargement-shell', '{pas du json');
    expect(shouldReload(storage, '/stands', 1000)).toBe(false);
  });
});

describe('handleNavigationError', () => {
  it('reloads the navigation target on a chunk failure', () => {
    const navigate = vi.fn();
    handleNavigationError(
      navigationError(new TypeError(CHUNK_FAILURES[0]), '/animateurs'),
      navigate,
      fakeStorage(),
      1000,
      true,
    );
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/animateurs');
  });

  it('does nothing for a navigation error that is not a chunk failure', () => {
    const navigate = vi.fn();
    handleNavigationError(
      navigationError(new Error('Cannot match any routes')),
      navigate,
      fakeStorage(),
      1000,
      true,
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('reloads at most once per target within the cooldown', () => {
    const navigate = vi.fn();
    const storage = fakeStorage();
    const failure = () => navigationError(new TypeError(CHUNK_FAILURES[0]));
    handleNavigationError(failure(), navigate, storage, 1000, true);
    handleNavigationError(failure(), navigate, storage, 2000, true);
    expect(navigate).toHaveBeenCalledOnce();
  });

  // A lift or a tunnel rejects the import with the very message a deleted
  // chunk produces. Reloading there swaps a dead link for the browser's
  // offline page and takes the whole session with it.
  it('leaves an offline browser alone', () => {
    const navigate = vi.fn();
    handleNavigationError(
      navigationError(new TypeError(CHUNK_FAILURES[0])),
      navigate,
      fakeStorage(),
      1000,
      false,
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  // …and must not spend the cooldown either: the one reload has to remain
  // available for the redeployment that comes once the network is back.
  it('does not burn the cooldown while offline', () => {
    const navigate = vi.fn();
    const storage = fakeStorage();
    const failure = () => navigationError(new TypeError(CHUNK_FAILURES[0]));
    handleNavigationError(failure(), navigate, storage, 1000, false);
    handleNavigationError(failure(), navigate, storage, 2000, true);
    expect(navigate).toHaveBeenCalledOnce();
  });

  // The shell being escaped is broken: `assign` would leave it one Back away,
  // and the cooldown would then refuse to heal it a second time.
  it('replaces the broken shell instead of stacking it in the history', () => {
    const replace = vi.fn();
    const assign = vi.fn();
    vi.stubGlobal('location', { replace, assign });
    try {
      handleNavigationError(
        navigationError(new TypeError(CHUNK_FAILURES[0]), '/animateurs'),
        undefined,
        fakeStorage(),
        1000,
        true,
      );
    } finally {
      vi.unstubAllGlobals();
    }
    expect(replace).toHaveBeenCalledExactlyOnceWith('/animateurs');
    expect(assign).not.toHaveBeenCalled();
  });
});
