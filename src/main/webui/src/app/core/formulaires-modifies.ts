// Which entries of this browser are not saved yet — the one answer to « is
// something typed that a solve would not see? », shared by the draft of the
// long forms (`shared/brouillon-dialog.ts`, which registers every form it
// protects) and by the Solveur page, which asks before « Calculer ».
//
// Two sources, because a solve can be launched from a tab other than the one
// holding the form: the forms open in this tab and modified, in memory; and
// the drafts left in storage for the current edition — a stand or a consigne
// typed in another tab, or a form a navigation closed before it was saved.
// A stored draft only counts while it is recent (see RECENT_DRAFT_MS) and
// while the store already loaded does not say its record is gone: an old or
// orphan draft is a net kept against an accident, not an entry in progress,
// and asking about it before every solve would teach the user to click
// through the question. A fiche animateur typed in another tab is not seen:
// its draft lives in that tab's sessionStorage, deliberately out of reach
// (docs/rgpd.md §7).

import { Injectable, Injector, Signal, computed, inject, signal } from '@angular/core';
import {
  DraftFormType,
  LOCAL_DRAFT_STORAGE,
  NEW_RECORD_ID,
  SESSION_DRAFT_STORAGE,
  draftKeyPrefix,
  draftKeys,
  readDraft,
} from './brouillon-formulaire';
import { ConsignesStore } from './consignes.store';
import { ReferenceDataStore } from './reference-data.store';

/** One unsaved entry: the draft key it is stored under, and how to name it to the user. */
export interface UnsavedEntry {
  key: string;
  label: string;
}

/** A stored draft older than this is not « being typed » any more. */
export const RECENT_DRAFT_MS = 10 * 60 * 1000;

const DRAFT_TYPES: readonly DraftFormType[] = ['animateur', 'stand', 'consigne'];

/**
 * How an unsaved entry is named: the form and the record's id — never an
 * identity, the sentence may end up in the notification log.
 */
export function unsavedEntryLabel(type: DraftFormType, recordId: string | null): string {
  const isNew = recordId === null || recordId === NEW_RECORD_ID;
  switch (type) {
    case 'animateur':
      return isNew
        ? $localize`:@@saisies.label.animateurNouveau:un nouvel animateur`
        : $localize`:@@saisies.label.animateur:la fiche de l'animateur ${recordId}:id:`;
    case 'stand':
      return isNew
        ? $localize`:@@saisies.label.standNouveau:un nouveau stand`
        : $localize`:@@saisies.label.stand:la fiche du stand ${recordId}:id:`;
    default: {
      // A consigne's record id is `<mode>.<date>`: the date is what the user knows it by.
      const date = isNew ? '' : recordId.slice(recordId.indexOf('.') + 1);
      return isNew
        ? $localize`:@@saisies.label.consigneNouvelle:une nouvelle consigne`
        : $localize`:@@saisies.label.consigne:la consigne du ${date}:date:`;
    }
  }
}

/**
 * Whether a stored draft counts as an entry in progress: recent, and not
 * about a record the loaded store says is gone. `exists` answers `null` when
 * it cannot tell — the store not loaded — and the draft is then counted:
 * asking once too often costs a click, missing a real entry costs it.
 */
export function isDraftInProgress(
  savedAt: string,
  now: number,
  recordId: string,
  exists: (recordId: string) => boolean | null,
): boolean {
  if (now - Date.parse(savedAt) > RECENT_DRAFT_MS) {
    return false;
  }
  return recordId === NEW_RECORD_ID || exists(recordId) !== false;
}

interface OpenForm {
  label: string;
  modified: Signal<boolean>;
}

@Injectable({ providedIn: 'root' })
export class ModifiedFormsRegistry {
  private readonly localStorage = inject(LOCAL_DRAFT_STORAGE);
  private readonly sessionStorage = inject(SESSION_DRAFT_STORAGE);
  /**
   * The stores are asked for lazily, when the Solveur page counts: every form
   * registers here, and a form must not drag the HTTP stack in to do so.
   */
  private readonly injector = inject(Injector);

  /**
   * Every registration, by draft key. A list, not one entry: the same record
   * can be open twice (a form reopened while the previous one is still
   * closing), and withdrawing one must not forget the other.
   */
  private readonly _open = signal<ReadonlyMap<string, readonly OpenForm[]>>(new Map());
  /** The forms open in this tab, modified or not, by draft key. */
  readonly open = this._open.asReadonly();

  /** The forms open in this tab whose entry differs from their opening, once per key. */
  readonly modified = computed<UnsavedEntry[]>(() =>
    [...this.open()]
      .filter(([, forms]) => forms.some((form) => form.modified()))
      .map(([key, forms]) => ({ key, label: forms[0].label })),
  );

  /**
   * Declares an open form, under its draft key; the returned function
   * withdraws that registration alone, and is what the form calls when it is
   * destroyed.
   */
  register(key: string, label: string, modified: Signal<boolean>): () => void {
    const form: OpenForm = { label, modified };
    this._open.update((open) => new Map(open).set(key, [...(open.get(key) ?? []), form]));
    return () =>
      this._open.update((open) => {
        const next = new Map(open);
        const remaining = (open.get(key) ?? []).filter((registered) => registered !== form);
        if (remaining.length === 0) {
          next.delete(key);
        } else {
          next.set(key, remaining);
        }
        return next;
      });
  }

  /**
   * Everything typed and not saved that a solve launched now would not see:
   * the modified forms of this tab, then the recent drafts of the current
   * edition left in storage, each once. Read on demand — the storage is no
   * signal — and without a request: the stores are read as they stand.
   */
  unsavedEntries(now: number = Date.now()): UnsavedEntry[] {
    const entries = new Map(this.modified().map((entry) => [entry.key, entry]));
    for (const storage of [this.localStorage, this.sessionStorage]) {
      for (const type of DRAFT_TYPES) {
        const prefix = draftKeyPrefix(type);
        for (const key of draftKeys(storage, prefix)) {
          if (entries.has(key)) {
            continue;
          }
          const envelope = readDraft(storage, key);
          const recordId = key.slice(prefix.length);
          if (
            envelope !== null &&
            isDraftInProgress(envelope.savedAt, now, recordId, (id) => this.exists(type, id))
          ) {
            entries.set(key, { key, label: unsavedEntryLabel(type, recordId) });
          }
        }
      }
    }
    return [...entries.values()];
  }

  /** What the stores already loaded say of a record; `null` when they hold nothing to tell. */
  private exists(type: DraftFormType, recordId: string): boolean | null {
    switch (type) {
      case 'animateur': {
        const animateurs = this.injector.get(ReferenceDataStore).animateurs();
        return animateurs.length === 0 ? null : animateurs.some((row) => row.id === recordId);
      }
      case 'stand': {
        const stands = this.injector.get(ReferenceDataStore).stands();
        return stands.length === 0 ? null : stands.some((row) => row.id === recordId);
      }
      default: {
        const consignes = this.injector.get(ConsignesStore);
        if (consignes.etat() === null) {
          return null;
        }
        const date = recordId.slice(recordId.indexOf('.') + 1);
        return consignes.consigneOf(date) !== null;
      }
    }
  }
}
