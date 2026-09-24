import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  LOCAL_DRAFT_STORAGE,
  SESSION_DRAFT_STORAGE,
  draftKey,
  writeDraft,
} from './brouillon-formulaire';
import { ConsignesStore } from './consignes.store';
import {
  ModifiedFormsRegistry,
  RECENT_DRAFT_MS,
  isDraftInProgress,
  unsavedEntryLabel,
} from './formulaires-modifies';
import { Animateur, ConsigneEdition, Stand } from './models';
import { ReferenceDataStore } from './reference-data.store';
import { memoryStorage } from './testing/brouillon';

describe('ModifiedFormsRegistry', () => {
  let local: ReturnType<typeof memoryStorage>;
  let session: ReturnType<typeof memoryStorage>;
  let registry: ModifiedFormsRegistry;
  const stands = signal<Stand[]>([]);
  const animateurs = signal<Animateur[]>([]);
  const etat = signal<{ consignes: ConsigneEdition[] } | null>(null);

  beforeEach(() => {
    local = memoryStorage();
    session = memoryStorage();
    stands.set([]);
    animateurs.set([]);
    etat.set(null);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: LOCAL_DRAFT_STORAGE, useValue: local },
        { provide: SESSION_DRAFT_STORAGE, useValue: session },
        { provide: ReferenceDataStore, useValue: { stands, animateurs } },
        {
          provide: ConsignesStore,
          useValue: {
            etat,
            consigneOf: (date: string) =>
              etat()?.consignes.find((consigne) => consigne.date === date) ?? null,
          },
        },
      ],
    });
    registry = TestBed.inject(ModifiedFormsRegistry);
  });

  it('lists an open form only while it is modified', () => {
    const modified = signal(false);
    registry.register('k1', 'la fiche du stand S1', modified);

    expect(registry.modified()).toEqual([]);

    modified.set(true);
    expect(registry.modified()).toEqual([{ key: 'k1', label: 'la fiche du stand S1' }]);
  });

  it('forgets a form once it is withdrawn', () => {
    const unregister = registry.register('k1', 'un nouveau stand', signal(true));

    unregister();

    expect(registry.modified()).toEqual([]);
    expect(registry.unsavedEntries()).toEqual([]);
  });

  it('keeps a key while one of two forms registered under it is still open', () => {
    const first = registry.register('k1', 'la fiche du stand S1', signal(false));
    registry.register('k1', 'la fiche du stand S1', signal(true));

    first();

    expect(registry.modified()).toEqual([{ key: 'k1', label: 'la fiche du stand S1' }]);
    expect(registry.open().get('k1')).toHaveLength(1);
  });

  it('counts the recent drafts left in storage for the current edition, from either storage', () => {
    writeDraft(local, draftKey('stand', 'S2'), {}, null);
    writeDraft(session, draftKey('animateur', null), {}, null);

    expect(registry.unsavedEntries().map((entry) => entry.label)).toEqual([
      'la fiche du stand S2',
      'un nouvel animateur',
    ]);
  });

  it('does not count a draft older than ten minutes', () => {
    writeDraft(
      local,
      draftKey('stand', 'S3'),
      {},
      null,
      new Date(Date.now() - RECENT_DRAFT_MS - 1000),
    );

    expect(registry.unsavedEntries()).toEqual([]);
  });

  it('does not count the draft of a record the loaded store no longer holds', () => {
    stands.set([{ id: 'S1' } as Stand]);
    writeDraft(local, draftKey('stand', 'S1'), {}, null);
    writeDraft(local, draftKey('stand', 'S-SUPPRIME'), {}, null);
    etat.set({ consignes: [] });
    writeDraft(local, draftKey('consigne', 'modifier.2026-07-14'), {}, null);

    expect(registry.unsavedEntries().map((entry) => entry.label)).toEqual(['la fiche du stand S1']);
  });

  it('names an open modified form once, even when it already wrote its draft', () => {
    const key = draftKey('consigne', 'modifier.2026-07-14');
    writeDraft(local, key, {}, null);
    registry.register(key, unsavedEntryLabel('consigne', 'modifier.2026-07-14'), signal(true));

    expect(registry.unsavedEntries()).toEqual([{ key, label: 'la consigne du 2026-07-14' }]);
  });

  it('names an animateur by id, never by identity', () => {
    expect(unsavedEntryLabel('animateur', 'A42')).toBe("la fiche de l'animateur A42");
  });
});

describe('isDraftInProgress', () => {
  const now = Date.parse('2026-07-14T12:00:00Z');
  const recent = new Date(now - 60_000).toISOString();

  it('counts a recent draft whose record is unknown to a store not loaded', () => {
    expect(isDraftInProgress(recent, now, 'S1', () => null)).toBe(true);
  });

  it('counts a creation whatever the store says', () => {
    expect(isDraftInProgress(recent, now, 'nouveau', () => false)).toBe(true);
  });

  it('drops an old draft and an orphan one', () => {
    expect(
      isDraftInProgress(new Date(now - RECENT_DRAFT_MS - 1).toISOString(), now, 'S1', () => true),
    ).toBe(false);
    expect(isDraftInProgress(recent, now, 'S1', () => false)).toBe(false);
  });
});
