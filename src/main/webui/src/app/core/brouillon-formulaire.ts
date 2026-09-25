// The auto-saved draft of the long admin forms — fiche animateur, stand,
// consigne: a net against the accident (a tab closed, a session expired, a
// reload), never a workspace. Nothing here reaches the server, the history,
// an export or the MCP; see docs/rgpd.md §7 for where each draft lives and
// how long.
//
// Pure functions over an injected storage, like `panel-collapse`: every read
// and every write tolerates a storage that is absent or that *throws* on the
// mere access (a hardened private window), so a form keeps working with no
// draft rather than failing.
//
// What counts as modified, what is restorable and what is persisted is each
// form's own module (`animateur-brouillon.ts`, `stand-brouillon.ts`,
// `consigne-brouillon.ts`); this one only knows envelopes and keys.

import { InjectionToken } from '@angular/core';
import { editionScopedKey } from './edition-courante';

/** What the drafts need from a `Storage`: enough to read, write and enumerate. */
export type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key'> & {
  readonly length: number;
};

/** The forms that keep a draft. */
export type DraftFormType = 'animateur' | 'stand' | 'consigne';

/**
 * Bumped whenever a draft's shape changes: an envelope of another version is
 * ignored — and dropped — rather than poured into a form that no longer has
 * the same fields.
 */
export const DRAFT_VERSION = 1;

/** A draft older than this is gone: a day is enough to recover from an accident, and bounds the exposure. */
export const DRAFT_LIFETIME_MS = 24 * 60 * 60 * 1000;

/** The prefix of every draft key, whatever the edition and the form. */
export const DRAFT_KEY_PREFIX = 'planning-equipes.brouillon';

/** The id segment of a draft for a record not created yet. */
export const NEW_RECORD_ID = 'nouveau';

/** What is stored: the draft, when it was written, and the precondition it was taken against. */
export interface DraftEnvelope<T> {
  v: number;
  /** ISO instant of the last write, what the banner reads as « du JJ/MM à HH:MM ». */
  savedAt: string;
  /** The record's `modifieLe` the draft was typed against — `null` for a creation. */
  modifieLe: string | null;
  draft: T;
}

/**
 * The browser storage of the given kind when it is reachable, `null` under
 * SSR or in a browser that throws on the access.
 */
export function defaultDraftStorage(kind: 'local' | 'session'): DraftStorage | null {
  try {
    if (kind === 'session') {
      return typeof sessionStorage === 'undefined' ? null : sessionStorage;
    }
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/**
 * Where the drafts carrying no personal data live: stand and consigne. The
 * browser's localStorage, so a tab closed by accident is covered too.
 */
export const LOCAL_DRAFT_STORAGE = new InjectionToken<DraftStorage | null>('LOCAL_DRAFT_STORAGE', {
  providedIn: 'root',
  factory: () => defaultDraftStorage('local'),
});

/**
 * Where the fiche animateur's draft lives, and it alone: sessionStorage,
 * which dies with the tab. An identity, a birth date — hence a minor's status
 * — and an e-mail must not stay on a shared régie computer once the browser
 * is closed.
 */
export const SESSION_DRAFT_STORAGE = new InjectionToken<DraftStorage | null>(
  'SESSION_DRAFT_STORAGE',
  { providedIn: 'root', factory: () => defaultDraftStorage('session') },
);

/**
 * The key prefix of one form's drafts in the current edition. Scoped like the
 * notification log (`editionScopedKey`): a 2026 draft is never offered on
 * 2025.
 */
export function draftKeyPrefix(
  type: DraftFormType,
  scope: (base: string) => string = editionScopedKey,
): string {
  return scope(`${DRAFT_KEY_PREFIX}.${type}`) + '#';
}

/** The key of one draft: at most one per (edition, form, record or « nouveau »). */
export function draftKey(
  type: DraftFormType,
  recordId: string | null,
  scope: (base: string) => string = editionScopedKey,
): string {
  return draftKeyPrefix(type, scope) + (recordId ?? NEW_RECORD_ID);
}

/**
 * Reads a draft. Nothing — `null` — for a missing, unreadable, other-version
 * or expired one; the last three are removed on the way, since no later read
 * could make anything of them.
 */
export function readDraft<T>(
  storage: DraftStorage | null,
  key: string,
  now: Date = new Date(),
): DraftEnvelope<T> | null {
  if (!storage) {
    return null;
  }
  try {
    const raw = storage.getItem(key);
    if (raw === null) {
      return null;
    }
    const envelope = parseEnvelope<T>(raw);
    if (envelope === null || isExpired(envelope, now)) {
      storage.removeItem(key);
      return null;
    }
    return envelope;
  } catch {
    return null;
  }
}

/** Writes a draft, ignoring a storage that refuses (private mode, quota): the form still works. */
export function writeDraft<T>(
  storage: DraftStorage | null,
  key: string,
  draft: T,
  modifieLe: string | null,
  now: Date = new Date(),
): void {
  if (!storage) {
    return;
  }
  const envelope: DraftEnvelope<T> = {
    v: DRAFT_VERSION,
    savedAt: now.toISOString(),
    modifieLe,
    draft,
  };
  try {
    storage.setItem(key, JSON.stringify(envelope));
  } catch {
    // Nothing to do: the form goes on, only without its net.
  }
}

export function deleteDraft(storage: DraftStorage | null, key: string): void {
  if (!storage) {
    return;
  }
  try {
    storage.removeItem(key);
  } catch {
    // A storage that cannot be written to holds nothing of ours either.
  }
}

/**
 * The keys of the drafts under `prefix` — every draft when left out. Read in
 * full before anything is removed: removing while walking `key(i)` shifts the
 * indexes under the loop.
 */
export function draftKeys(
  storage: DraftStorage | null,
  prefix: string = DRAFT_KEY_PREFIX,
): string[] {
  if (!storage) {
    return [];
  }
  try {
    const keys: string[] = [];
    for (let index = 0; index < storage.length; index++) {
      const key = storage.key(index);
      if (key?.startsWith(prefix)) {
        keys.push(key);
      }
    }
    return keys;
  } catch {
    return [];
  }
}

/** Removes every draft past its lifetime, or unreadable, whatever its edition. Run when the shell starts. */
export function purgeExpiredDrafts(storage: DraftStorage | null, now: Date = new Date()): void {
  for (const key of draftKeys(storage)) {
    // `readDraft` removes what it cannot offer.
    readDraft(storage, key, now);
  }
}

/**
 * How many times every draft was purged in this page's life. A form captures
 * it when it opens and writes nothing once it moved: without it, a write
 * racing the logout — the auto-save timer, or the flush of a dialog the
 * logout's navigation destroys — would put back the very entry just purged.
 */
let purgeGeneration = 0;

/** The current purge generation, compared by a form with the one it opened under. */
export function draftPurgeGeneration(): number {
  return purgeGeneration;
}

/**
 * Removes every draft of every edition: the logout of a shared computer —
 * this tab's, or another's announced here. Authoritative: no form opened
 * before it writes a draft again.
 */
export function purgeAllDrafts(storage: DraftStorage | null): void {
  purgeGeneration++;
  for (const key of draftKeys(storage)) {
    deleteDraft(storage, key);
  }
}

/**
 * The record ids of one form's drafts in the current edition, `nouveau`
 * excepted — what a page compares with the records it holds to find the
 * drafts of a deleted one.
 */
export function recordsWithDraft(
  storage: DraftStorage | null,
  type: DraftFormType,
  scope: (base: string) => string = editionScopedKey,
): { key: string; recordId: string }[] {
  const prefix = draftKeyPrefix(type, scope);
  return draftKeys(storage, prefix)
    .map((key) => ({ key, recordId: key.slice(prefix.length) }))
    .filter(({ recordId }) => recordId !== NEW_RECORD_ID && recordId !== '');
}

/**
 * Removes the drafts of records that no longer exist — deleted from another
 * tab, another computer, the MCP — and returns their ids so the page can say
 * so. Such a draft is never offered: restoring it would either recreate the
 * record behind the user's back or land on nothing, and the decision is to
 * drop it with a word rather than re-propose it as a creation.
 *
 * Only called by a page once its records are known to be loaded: an empty
 * list read from a failed request would otherwise erase every draft.
 */
export function purgeOrphanDrafts(
  storage: DraftStorage | null,
  type: DraftFormType,
  exists: (recordId: string) => boolean,
  scope: (base: string) => string = editionScopedKey,
): string[] {
  const orphans = recordsWithDraft(storage, type, scope).filter(
    ({ recordId }) => !exists(recordId),
  );
  for (const { key } of orphans) {
    deleteDraft(storage, key);
  }
  return orphans.map(({ recordId }) => recordId);
}

function parseEnvelope<T>(raw: string): DraftEnvelope<T> | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) {
      return null;
    }
    const candidate = parsed as Partial<DraftEnvelope<T>>;
    if (
      candidate.v !== DRAFT_VERSION ||
      typeof candidate.savedAt !== 'string' ||
      Number.isNaN(Date.parse(candidate.savedAt)) ||
      !(candidate.modifieLe === null || typeof candidate.modifieLe === 'string') ||
      candidate.draft === undefined
    ) {
      return null;
    }
    return candidate as DraftEnvelope<T>;
  } catch {
    return null;
  }
}

function isExpired(envelope: DraftEnvelope<unknown>, now: Date): boolean {
  return now.getTime() - Date.parse(envelope.savedAt) > DRAFT_LIFETIME_MS;
}
