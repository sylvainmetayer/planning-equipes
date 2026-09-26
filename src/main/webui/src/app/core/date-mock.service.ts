// Development- and staging-only override of the server's notion of today
// (`/api/horloge`, issue #297), set from Paramètres › Instance.
//
// The mode jour J screen is entirely about what is still ahead *today*, so
// without this it could only be exercised on the day of the event. What is
// substituted is the server's date, not the browser's: that screen reads its
// reference moment from the server on purpose, and a faked `Date.now()` here
// would prove nothing about the real path.
//
// Persisted server-side, like the manual solve budget next door: a setting kept
// in one browser looks inconsistent from the next, and one kept in a process
// vanishes under a hot reload.
//
// `modifiable` comes from the server too. It is what the interface hides the
// field on — a courtesy, never the guard: the guard is the write endpoint,
// which refuses on any instance neither under `quarkus:dev` nor launched with
// `HORLOGE_SIMULEE_AUTORISEE=true`.

import { Injectable, computed, inject, signal } from '@angular/core';
import { HorlogeApi } from './api/horloge-api';
import { DateJourJView } from './models';

/**
 * Query param value, element id and fragment the toolbar indicator deep-links
 * to: the date field of Paramètres › Instance.
 */
export const TODAY_ANCHOR = 'date-du-jour';

/** The card of Paramètres › Instance carrying the field. */
export const CLOCK_CARD_ANCHOR = 'horloge';

@Injectable({ providedIn: 'root' })
export class DateMockService {
  /** The frozen date (`AAAA-MM-JJ`), or `''` when the real clock is in use. */
  private readonly _dateDuJour = signal('');
  readonly dateDuJour = this._dateDuJour.asReadonly();
  /**
   * The frozen time of day (`HH:mm`), or `''` while the wall clock gives it.
   *
   * Named for what it is rather than after its wire key: « du » is a French
   * function word, which a declared name of this repository does not carry.
   */
  private readonly _heureMock = signal('');
  readonly heureMock = this._heureMock.asReadonly();
  /** `2026-07-08` or `2026-07-08 14:30` — what the warnings print. */
  readonly libelle = computed(() =>
    [this.dateDuJour(), this.heureMock()].filter(Boolean).join(' '),
  );
  /** Whether this server would accept a frozen date at all. */
  private readonly _modifiable = signal(false);
  readonly modifiable = this._modifiable.asReadonly();
  /** Whether the date is frozen right now — what the toolbar indicator watches. */
  readonly actif = computed(() => this.dateDuJour() !== '');

  private readonly api = inject(HorlogeApi);

  constructor() {
    void this.refresh().catch(() => {
      // Unreachable at startup: stay on "real clock, not modifiable", which is
      // the safe reading. A caller awaiting refresh() directly (Paramètres ›
      // Instance) still sees the error and reports it.
    });
  }

  async refresh(): Promise<void> {
    this.apply(await this.api.read());
  }

  /**
   * An empty date hands the whole clock back, time included; an empty time
   * keeps the wall clock's. The server answers 400 where the simulated clock is not allowed.
   */
  async set(date: string, heure = ''): Promise<void> {
    this.apply(await this.api.write(date || null, date && heure ? heure : null));
  }

  private apply(view: DateJourJView): void {
    this._dateDuJour.set(view.dateDuJour ?? '');
    this._heureMock.set(view.heureDuJour ?? '');
    this._modifiable.set(view.modifiable);
  }
}
