import { describe, expect, it } from 'vitest';
import {
  DRAFT_LIFETIME_MS,
  DRAFT_VERSION,
  deleteDraft,
  draftKey,
  draftKeys,
  purgeAllDrafts,
  purgeExpiredDrafts,
  purgeOrphanDrafts,
  readDraft,
  recordsWithDraft,
  writeDraft,
} from './brouillon-formulaire';
import { memoryStorage, refusingStorage } from './testing/brouillon';

/** The edition scoping of `editionScopedKey`, without reaching for the browser's own storage. */
const edition2026 = (base: string) => `${base}.ed-2026`;
const edition2025 = (base: string) => `${base}.ed-2025`;

const NOON = new Date('2026-07-14T12:00:00Z');

describe('brouillon-formulaire', () => {
  describe('keys', () => {
    it('scopes a draft by edition, by form and by record', () => {
      const keys = new Set([
        draftKey('stand', 's1', edition2026),
        draftKey('stand', 's1', edition2025),
        draftKey('stand', 's2', edition2026),
        draftKey('consigne', 's1', edition2026),
        draftKey('stand', null, edition2026),
      ]);

      expect(keys.size).toBe(5);
    });

    it('names the draft of a creation « nouveau »', () => {
      expect(draftKey('animateur', null, edition2026)).toBe(
        'planning-equipes.brouillon.animateur.ed-2026#nouveau',
      );
    });
  });

  describe('reading and writing', () => {
    it('reads back what was written, with its moment and its precondition', () => {
      const storage = memoryStorage();
      const key = draftKey('stand', 's1', edition2026);

      writeDraft(storage, key, { nom: 'Buvette' }, '2026-07-01T08:00:00Z', NOON);

      expect(readDraft(storage, key, NOON)).toEqual({
        v: DRAFT_VERSION,
        savedAt: NOON.toISOString(),
        modifieLe: '2026-07-01T08:00:00Z',
        draft: { nom: 'Buvette' },
      });
    });

    it('offers nothing when nothing was written', () => {
      expect(readDraft(memoryStorage(), 'absent', NOON)).toBeNull();
    });

    it('deletes a draft', () => {
      const storage = memoryStorage();
      writeDraft(storage, 'k', { a: 1 }, null, NOON);

      deleteDraft(storage, 'k');

      expect(storage.entries.size).toBe(0);
    });

    it('ignores and removes a draft of an older version', () => {
      const storage = memoryStorage({
        k: JSON.stringify({ v: 0, savedAt: NOON.toISOString(), modifieLe: null, draft: {} }),
      });

      expect(readDraft(storage, 'k', NOON)).toBeNull();
      expect(storage.entries.has('k')).toBe(false);
    });

    it('ignores and removes an unreadable draft', () => {
      const storage = memoryStorage({ k: '{not json' });

      expect(readDraft(storage, 'k', NOON)).toBeNull();
      expect(storage.entries.has('k')).toBe(false);
    });

    it('still offers a 23-hour-old draft, no longer one past 24 hours', () => {
      const storage = memoryStorage();
      writeDraft(storage, 'k', { a: 1 }, null, NOON);

      const twentyThreeHours = new Date(NOON.getTime() + 23 * 3600 * 1000);
      expect(readDraft(storage, 'k', twentyThreeHours)).not.toBeNull();

      const beyond = new Date(NOON.getTime() + DRAFT_LIFETIME_MS + 1);
      expect(readDraft(storage, 'k', beyond)).toBeNull();
      expect(storage.entries.has('k')).toBe(false);
    });
  });

  describe('storage unavailable', () => {
    it('never throws: absent', () => {
      expect(() => writeDraft(null, 'k', {}, null)).not.toThrow();
      expect(readDraft(null, 'k')).toBeNull();
      expect(() => purgeAllDrafts(null)).not.toThrow();
      expect(draftKeys(null)).toEqual([]);
    });

    it('never throws: a hardened browser refusing the access', () => {
      const refusing = refusingStorage();

      expect(() => writeDraft(refusing, 'k', {}, null)).not.toThrow();
      expect(readDraft(refusing, 'k')).toBeNull();
      expect(() => deleteDraft(refusing, 'k')).not.toThrow();
      expect(() => purgeExpiredDrafts(refusing)).not.toThrow();
      expect(() => purgeAllDrafts(refusing)).not.toThrow();
      expect(purgeOrphanDrafts(refusing, 'stand', () => false)).toEqual([]);
    });
  });

  describe('purges', () => {
    it('at start-up, removes the expired drafts of every edition and keeps the others', () => {
      const storage = memoryStorage({ 'other.key': 'untouched' });
      const old = new Date(NOON.getTime() - DRAFT_LIFETIME_MS - 60_000);
      writeDraft(storage, draftKey('stand', 's1', edition2025), {}, null, old);
      writeDraft(storage, draftKey('stand', 's2', edition2026), {}, null, NOON);

      purgeExpiredDrafts(storage, NOON);

      expect([...storage.entries.keys()].sort((a, b) => a.localeCompare(b))).toEqual(
        ['other.key', draftKey('stand', 's2', edition2026)].sort((a, b) => a.localeCompare(b)),
      );
    });

    it('at logout, removes every draft and nothing else', () => {
      const storage = memoryStorage({ 'planning-equipes.editionId': 'ed-2026' });
      writeDraft(storage, draftKey('stand', 's1', edition2025), {}, null, NOON);
      writeDraft(storage, draftKey('consigne', null, edition2026), {}, null, NOON);

      purgeAllDrafts(storage);

      expect([...storage.entries.keys()]).toEqual(['planning-equipes.editionId']);
    });

    it('lists the records of one form in the current edition only, the creation aside', () => {
      const storage = memoryStorage();
      writeDraft(storage, draftKey('stand', 's1', edition2026), {}, null, NOON);
      writeDraft(storage, draftKey('stand', null, edition2026), {}, null, NOON);
      writeDraft(storage, draftKey('stand', 's9', edition2025), {}, null, NOON);
      writeDraft(storage, draftKey('consigne', 'modifier.x', edition2026), {}, null, NOON);

      expect(recordsWithDraft(storage, 'stand', edition2026).map((r) => r.recordId)).toEqual([
        's1',
      ]);
    });

    it('removes the draft of a record deleted meanwhile, and names it', () => {
      const storage = memoryStorage();
      writeDraft(storage, draftKey('stand', 'kept', edition2026), {}, null, NOON);
      writeDraft(storage, draftKey('stand', 'deleted', edition2026), {}, null, NOON);

      const orphans = purgeOrphanDrafts(storage, 'stand', (id) => id === 'kept', edition2026);

      expect(orphans).toEqual(['deleted']);
      expect([...storage.entries.keys()]).toEqual([draftKey('stand', 'kept', edition2026)]);
    });
  });
});
