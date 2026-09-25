// The freeze of the referential (ADR 0052), shared by every screen that
// writes a freezable family: the forms show the frozen fields read-only with
// a padlock, the imports say so before a file is read, the home card and the
// Paramètres switches freeze and lift. Server state, re-read on demand and
// never persisted in the browser — the server refuses the write anyway
// (`409 REFERENTIEL_FIGE`), the screens only say it before the click.

import { Injectable, computed, inject, signal } from '@angular/core';
import { EditionsApi } from './api/editions-api';
import { errorMessage } from './error-message';
import { EtatGel, FreezeFamily } from './models';

/** The four families, in the server's order — what a screen lists before the first read lands. */
export const FREEZE_FAMILIES: readonly FreezeFamily[] = [
  'STANDS',
  'CRENEAUX',
  'TYPOLOGIES_EMPLACEMENTS',
  'COMPETENCES',
];

@Injectable({ providedIn: 'root' })
export class GelReferentielStore {
  private readonly api = inject(EditionsApi);

  /** Every family, frozen or not; empty until the first read. */
  private readonly _states = signal<EtatGel[]>([]);
  readonly states = this._states.asReadonly();
  private readonly _error = signal('');
  readonly error = this._error.asReadonly();
  private readonly _busy = signal(false);
  readonly busy = this._busy.asReadonly();

  private pending: Promise<void> | null = null;
  private loaded = false;

  /** Whether any family is frozen — what the milestone invitation reads. */
  readonly anyFrozen = computed(() => this.states().some((state) => state.fige));

  /** The frozen families, in the server's order — what a whole-edition notice names. */
  readonly frozenFamilies = computed(() =>
    this.states()
      .filter((state) => state.fige)
      .map((state) => state.famille),
  );

  /** The freeze of `family` when it holds, `null` while the family is open. */
  frozen(family: FreezeFamily): EtatGel | null {
    return this.states().find((state) => state.famille === family && state.fige) ?? null;
  }

  isFrozen(family: FreezeFamily): boolean {
    return this.frozen(family) !== null;
  }

  /** Reads the freeze once for the page's lifetime; a screen calls it on opening. */
  ensureLoaded(): Promise<void> {
    if (this.loaded) {
      return Promise.resolve();
    }
    return this.reload();
  }

  /** Re-reads the freeze — after a refusal told this browser another session froze a family. */
  reload(): Promise<void> {
    this.pending ??= this.read().finally(() => (this.pending = null));
    return this.pending;
  }

  private async read(): Promise<void> {
    try {
      const states = await this.api.gel();
      // A stubbed or unexpected answer reads as « nothing frozen », never as a crash.
      this._states.set(Array.isArray(states) ? states : []);
      this._error.set('');
      this.loaded = true;
    } catch (error) {
      this._error.set(errorMessage(error));
    }
  }

  /** Freezes `family`; the failure propagates to the caller, which says it. */
  async freeze(family: FreezeFamily): Promise<void> {
    this._busy.set(true);
    try {
      const state = await this.api.freeze(family);
      this.replace(state);
    } finally {
      this._busy.set(false);
    }
  }

  /** Lifts the freeze of `family`; the failure propagates to the caller, which says it. */
  async lift(family: FreezeFamily): Promise<void> {
    this._busy.set(true);
    try {
      await this.api.lift(family);
      const current = this.states().find((state) => state.famille === family);
      if (current) {
        this.replace({ ...current, fige: false, figeLe: null });
      } else {
        await this.reload();
      }
    } finally {
      this._busy.set(false);
    }
  }

  private replace(state: EtatGel): void {
    const others = this.states().filter((candidate) => candidate.famille !== state.famille);
    this._states.set(
      [...others, state].sort(
        (left, right) =>
          FREEZE_FAMILIES.indexOf(left.famille) - FREEZE_FAMILIES.indexOf(right.famille),
      ),
    );
    this.loaded = true;
  }
}

/**
 * The store, loaded for the page that asks: every component that consults it
 * injects it through this rather than `inject`, so a padlock holds on a fresh
 * reload even when nothing else on the page — the notice sits inside the
 * frozen branch — has read the freeze yet. One read per page lifetime.
 */
export function injectGelReferentiel(): GelReferentielStore {
  const store = inject(GelReferentielStore);
  void store.ensureLoaded();
  return store;
}
