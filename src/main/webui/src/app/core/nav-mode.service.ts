// The signal side of the menu mode (simple / avancé): what the drawer's
// toolbar binds to. The persistence lives in `nav-mode.ts`.

import { Injectable, signal } from '@angular/core';

import { defaultNavStorage } from './nav-collapse';
import { NavMode, nextNavMode, readNavMode, writeNavMode } from './nav-mode';

@Injectable({ providedIn: 'root' })
export class NavModeService {
  private readonly storage = defaultNavStorage();

  private readonly _mode = signal<NavMode>(readNavMode(this.storage));

  /** What the user asked for: `simple` (the default) or `avance`. */
  readonly mode = this._mode.asReadonly();

  /** Applies and remembers an explicit choice. */
  set(mode: NavMode): void {
    this._mode.set(mode);
    writeNavMode(this.storage, mode);
  }

  /** One control for the two states: simple → avancé → simple. */
  toggle(): NavMode {
    const next = nextNavMode(this._mode());
    this.set(next);
    return next;
  }
}
