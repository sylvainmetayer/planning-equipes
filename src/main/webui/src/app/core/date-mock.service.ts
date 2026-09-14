// Development-only override of the server's notion of today
// (`/api/debug/date-du-jour`, issue #297).
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
// which refuses on any instance not launched with `quarkus:dev`.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { DateJourJView } from './models';

/** Query param and anchor the toolbar indicator deep-links to. */
export const TODAY_ANCHOR = 'date-du-jour';

@Injectable({ providedIn: 'root' })
export class DateMockService {
  /** The frozen date (`AAAA-MM-JJ`), or `''` when the real clock is in use. */
  private readonly _dateDuJour = signal('');
  readonly dateDuJour = this._dateDuJour.asReadonly();
  /** The frozen time of day (`HH:mm`), or `''` while the wall clock gives it. */
  private readonly _heureDuJour = signal('');
  readonly heureDuJour = this._heureDuJour.asReadonly();
  /** `2026-07-08` or `2026-07-08 14:30` — what the warnings print. */
  readonly libelle = computed(() =>
    [this.dateDuJour(), this.heureDuJour()].filter(Boolean).join(' '),
  );
  /** Whether this server would accept a frozen date at all. */
  private readonly _modifiable = signal(false);
  readonly modifiable = this._modifiable.asReadonly();
  /** Whether the date is frozen right now — what the toolbar indicator watches. */
  readonly actif = computed(() => this.dateDuJour() !== '');

  private readonly api = inject(ApiService);

  constructor() {
    void this.refresh().catch(() => {
      // Unreachable at startup: stay on "real clock, not modifiable", which is
      // the safe reading. A caller awaiting refresh() directly (the Débogage
      // page) still sees the error and reports it.
    });
  }

  async refresh(): Promise<void> {
    this.apply(await this.api.get<DateJourJView>('/api/debug/date-du-jour'));
  }

  /**
   * An empty date hands the whole clock back, time included; an empty time
   * keeps the wall clock's. The server answers 400 outside dev mode.
   */
  async set(date: string, heure = ''): Promise<void> {
    this.apply(
      await this.api.put<DateJourJView>('/api/debug/date-du-jour', {
        dateDuJour: date || null,
        heureDuJour: date && heure ? heure : null,
      }),
    );
  }

  private apply(view: DateJourJView): void {
    this._dateDuJour.set(view.dateDuJour ?? '');
    this._heureDuJour.set(view.heureDuJour ?? '');
    this._modifiable.set(view.modifiable);
  }
}
