// Spec fixtures for the forms that keep an auto-saved draft
// (`core/brouillon-formulaire.ts`, `shared/brouillon-dialog.ts`).

import { Provider } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { DraftStorage, LOCAL_DRAFT_STORAGE, SESSION_DRAFT_STORAGE } from '../brouillon-formulaire';

/** A storage that is there and works, in memory — a spec's own, never the browser's. */
export function memoryStorage(initial: Record<string, string> = {}): DraftStorage & {
  readonly entries: Map<string, string>;
} {
  const entries = new Map(Object.entries(initial));
  return {
    entries,
    get length() {
      return entries.size;
    },
    key: (index) => [...entries.keys()][index] ?? null,
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => {
      entries.set(key, value);
    },
    removeItem: (key) => {
      entries.delete(key);
    },
  };
}

/** A hardened browser: touching storage *throws* rather than returning null. */
export function refusingStorage(): DraftStorage {
  const refuse = (): never => {
    throw new Error('storage refused');
  };
  return {
    get length(): number {
      return refuse();
    },
    key: refuse,
    getItem: refuse,
    setItem: refuse,
    removeItem: refuse,
  };
}

/**
 * The drafts switched off: a spec about something else must neither read what
 * a previous test left in jsdom's storage nor leave anything behind.
 */
export function noDraftStorage(): Provider[] {
  return [
    { provide: LOCAL_DRAFT_STORAGE, useValue: null },
    { provide: SESSION_DRAFT_STORAGE, useValue: null },
  ];
}

/** The part of a `MatDialogRef` a protected form uses, with the two streams under the spec's hand. */
export interface FakeDialogRef {
  close: (result?: unknown) => void;
  disableClose: boolean | undefined;
  backdropClick: () => Subject<MouseEvent>;
  keydownEvents: () => Subject<KeyboardEvent>;
  readonly backdrop: Subject<MouseEvent>;
  readonly keys: Subject<KeyboardEvent>;
}

export function fakeDialogRef(close: (result?: unknown) => void): FakeDialogRef {
  const backdrop = new Subject<MouseEvent>();
  const keys = new Subject<KeyboardEvent>();
  return {
    close,
    disableClose: undefined,
    backdropClick: () => backdrop,
    keydownEvents: () => keys,
    backdrop,
    keys,
  };
}

/** The same, typed as what the dialog injects. */
export function asDialogRef(ref: FakeDialogRef): MatDialogRef<unknown, unknown> {
  return ref as unknown as MatDialogRef<unknown, unknown>;
}
