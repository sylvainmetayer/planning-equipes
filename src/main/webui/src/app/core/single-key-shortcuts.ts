// Whether the single-key shortcuts (`g` + letter, `/`, `?`) are armed on this
// browser (WCAG 2.1.4, Character Key Shortcuts).
//
// « Active everywhere except in a text field » is none of the three things the
// criterion accepts: a person dictating by voice fires one with every word
// spoken outside a field, and a tremor turns a repeated key into a navigation.
// So they can be turned off — the lightest of the three answers, and enough:
// each of the twenty destinations has another way in (the drawer, the Ctrl+K
// palette, the URL). Modifier combinations stay armed: Ctrl+K cannot be typed
// by accident.
//
// A chrome preference on the model of `nav-collapse`: this browser's, not the
// account's, in localStorage.

import { Injectable, signal } from '@angular/core';
import { defaultNavStorage } from './nav-collapse';

const STORAGE_KEY = 'planning-equipes.raccourcis.toucheSimple';

type PreferenceStorage = Pick<Storage, 'getItem' | 'setItem'> | null;

/**
 * Reads the stored choice. Anything but the explicit « off » reads as armed:
 * the shortcuts are on by default, as they have always been.
 */
export function readSingleKeyShortcuts(storage: PreferenceStorage): boolean {
  if (!storage) {
    return true;
  }
  try {
    return storage.getItem(STORAGE_KEY) !== 'off';
  } catch {
    return true;
  }
}

/** Writes it, ignoring a storage that refuses to be written to (private mode, quota). */
export function writeSingleKeyShortcuts(storage: PreferenceStorage, enabled: boolean): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(STORAGE_KEY, enabled ? 'on' : 'off');
  } catch {
    // The switch still applies, it just forgets on reload.
  }
}

@Injectable({ providedIn: 'root' })
export class SingleKeyShortcutsService {
  private readonly storage = defaultNavStorage();
  private readonly _enabled = signal(readSingleKeyShortcuts(this.storage));

  /** Whether `g`, `/` and `?` act outside a text field on this browser. */
  readonly enabled = this._enabled.asReadonly();

  /** Applies and remembers an explicit choice. */
  set(enabled: boolean): void {
    this._enabled.set(enabled);
    writeSingleKeyShortcuts(this.storage, enabled);
  }
}
