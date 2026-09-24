// A logout said to every tab of this browser, so each one drops what it keeps
// of the session — the sessionStorage draft of a fiche animateur, above all,
// which the tab that logged out cannot reach (docs/rgpd.md §7).
//
// A BroadcastChannel when the browser has one; otherwise a `storage` event
// raised by writing then removing one localStorage key, which carries no data
// but a moment. Every access is guarded: a hardened browser that refuses both
// simply keeps each tab's own purge.

import { InjectionToken } from '@angular/core';

const CHANNEL = 'planning-equipes.session';
const MESSAGE = 'logout';
/** Written then removed at once: only the event it raises matters. */
export const LOGOUT_SIGNAL_KEY = 'planning-equipes.session.logout';

/** The browser APIs used, injectable so the specs drive them. */
export interface SessionEndBus {
  channel: (() => Pick<BroadcastChannel, 'postMessage' | 'close'> & EventTarget) | null;
  storage: Pick<Storage, 'setItem' | 'removeItem'> | null;
  window: Pick<Window, 'addEventListener' | 'removeEventListener'> | null;
}

export function defaultSessionEndBus(): SessionEndBus {
  let storage: Storage | null;
  try {
    storage = typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    storage = null;
  }
  return {
    channel: typeof BroadcastChannel === 'undefined' ? null : () => new BroadcastChannel(CHANNEL),
    storage,
    window: typeof window === 'undefined' ? null : window,
  };
}

/** The bus the shell uses, replaced by the specs. */
export const SESSION_END_BUS = new InjectionToken<SessionEndBus>('SESSION_END_BUS', {
  providedIn: 'root',
  factory: defaultSessionEndBus,
});

/** Tells the other tabs this browser's session is over. */
export function announceLogout(bus: SessionEndBus = defaultSessionEndBus()): void {
  try {
    if (bus.channel) {
      const channel = bus.channel();
      channel.postMessage(MESSAGE);
      channel.close();
      return;
    }
    bus.storage?.setItem(LOGOUT_SIGNAL_KEY, String(Date.now()));
    bus.storage?.removeItem(LOGOUT_SIGNAL_KEY);
  } catch {
    // Nothing to do: the other tabs purge on their own logout or at 24 h.
  }
}

/** Calls `onLogout` when another tab announces a logout; returns the unsubscription. */
export function onLogoutElsewhere(
  onLogout: () => void,
  bus: SessionEndBus = defaultSessionEndBus(),
): () => void {
  try {
    if (bus.channel) {
      const channel = bus.channel();
      const listener = (event: Event) => {
        if ((event as MessageEvent).data === MESSAGE) {
          onLogout();
        }
      };
      channel.addEventListener('message', listener);
      return () => {
        channel.removeEventListener('message', listener);
        channel.close();
      };
    }
    const target = bus.window;
    if (!target) {
      return () => undefined;
    }
    const listener = (event: Event) => {
      const storageEvent = event as StorageEvent;
      if (storageEvent.key === LOGOUT_SIGNAL_KEY && storageEvent.newValue !== null) {
        onLogout();
      }
    };
    target.addEventListener('storage', listener);
    return () => target.removeEventListener('storage', listener);
  } catch {
    return () => undefined;
  }
}
