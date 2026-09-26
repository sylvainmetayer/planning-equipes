// Whether this browser has read the news of the running version: what lights,
// after an update, the « nouveautés » link next to the drawer's Aide entry and
// the dot on the foot's news link.
//
// A chrome preference, on the model of `nav-collapse`: this browser's, never
// shareable, and harmless when lost. Nothing here may throw — a storage
// refused (private mode, quota, a blocked site) simply shows no marker.

import { Injectable, signal } from '@angular/core';

import { APP_VERSION } from '../version';
import { defaultNavStorage } from './nav-collapse';

const STORAGE_KEY = 'planning-equipes.nouveautes.vues';

type NewsStorage = Pick<Storage, 'getItem' | 'setItem'> | null;

/**
 * True when the version running differs from the one this browser last read
 * the news of. A first visit ever records the running version silently: there
 * is nothing *new* to someone who has never seen the previous one, and a
 * marker lit on every fresh browser would teach people to ignore it.
 */
export function readNewsUnseen(storage: NewsStorage, version: string): boolean {
  if (!storage) {
    return false;
  }
  try {
    const seen = storage.getItem(STORAGE_KEY);
    if (seen === null) {
      storage.setItem(STORAGE_KEY, version);
      return false;
    }
    return seen !== version;
  } catch {
    return false;
  }
}

/** Records that the news of this version were read. */
export function writeNewsSeen(storage: NewsStorage, version: string): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(STORAGE_KEY, version);
  } catch {
    // Nothing to do: the marker goes out for this visit, it just comes back on reload.
  }
}

@Injectable({ providedIn: 'root' })
export class NewsSeenService {
  private readonly storage = defaultNavStorage();
  private readonly _unseen = signal(readNewsUnseen(this.storage, APP_VERSION));

  /** The running version brought news this browser has not opened yet. */
  readonly unseen = this._unseen.asReadonly();

  /** Called by the news page: once it is open, the marker goes. */
  markSeen(): void {
    this._unseen.set(false);
    writeNewsSeen(this.storage, APP_VERSION);
  }
}
