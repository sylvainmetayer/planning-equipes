// The glue shared by the three long forms: auto-save, the found draft, the
// protected close. Driven without rendering a form — a signal stands for it.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DraftStorage,
  LOCAL_DRAFT_STORAGE,
  SESSION_DRAFT_STORAGE,
  draftKey,
  purgeAllDrafts,
  writeDraft,
} from '../core/brouillon-formulaire';
import { NotificationService } from '../core/notification.service';
import {
  asDialogRef,
  fakeDialogRef,
  memoryStorage,
  refusingStorage,
} from '../core/testing/brouillon';
import { FormDraft, reportOrphanDrafts } from './brouillon-dialog';
import { ConfirmService } from './confirm-dialog';

interface Entry {
  nom: string;
}

const OPENING: Entry = { nom: 'Buvette' };

function mount(
  options: {
    storage?: DraftStorage | null;
    type?: 'stand' | 'animateur';
    modifieLe?: string | null;
    confirmed?: boolean;
  } = {},
) {
  const storage = options.storage === undefined ? memoryStorage() : options.storage;
  const ask = vi.fn(async () => options.confirmed ?? true);
  const close = vi.fn();
  const ref = fakeDialogRef(close);
  const type = options.type ?? 'stand';
  const other = memoryStorage();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ConfirmService, useValue: { ask } },
      // The storage under test sits where the form type sends it; the other
      // one is a storage of its own, so a draft written there shows.
      { provide: LOCAL_DRAFT_STORAGE, useValue: type === 'stand' ? storage : other },
      { provide: SESSION_DRAFT_STORAGE, useValue: type === 'animateur' ? storage : other },
    ],
  });
  const entry = signal<Entry>(OPENING);
  const formDraft = TestBed.runInInjectionContext(
    () =>
      new FormDraft<Entry>({
        type,
        recordId: 's1',
        modifieLe: options.modifieLe ?? '2026-07-01T08:00:00Z',
        state: () => entry(),
        modified: () => entry().nom !== OPENING.nom,
        read: (raw) => (typeof (raw as Entry)?.nom === 'string' ? (raw as Entry) : null),
        apply: (draft) => entry.set(draft),
        dialogRef: asDialogRef(ref),
        cancelResult: false,
      }),
  );
  TestBed.tick();
  return { formDraft, entry, storage, other, ask, close, ref };
}

const KEY = draftKey('stand', 's1');

function storageWith(draft: unknown, modifieLe = '2026-07-01T08:00:00Z') {
  const storage = memoryStorage();
  writeDraft(storage, KEY, draft, modifieLe);
  return storage;
}

describe('FormDraft', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }));
  afterEach(() => vi.useRealTimers());

  describe('auto-save', () => {
    it('writes the draft a second after the last change, not before', () => {
      const { entry, storage } = mount();

      entry.set({ nom: 'Buvette du parc' });
      TestBed.tick();
      vi.advanceTimersByTime(900);
      expect(storage!.getItem(KEY)).toBeNull();

      vi.advanceTimersByTime(200);
      expect(JSON.parse(storage!.getItem(KEY)!).draft).toEqual({ nom: 'Buvette du parc' });
    });

    it('puts an animateur draft in sessionStorage, whatever the caller: the type decides', () => {
      const { entry, storage, other } = mount({ type: 'animateur' });

      entry.set({ nom: 'Identité en cours' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      expect(storage!.getItem(draftKey('animateur', 's1'))).not.toBeNull();
      expect(other.length).toBe(0);
    });

    it('puts a stand draft in localStorage', () => {
      const { entry, storage, other } = mount();

      entry.set({ nom: 'Autre' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      expect(storage!.getItem(KEY)).not.toBeNull();
      expect(other.length).toBe(0);
    });

    it('does not overwrite the draft found while its banner waits for an answer', () => {
      const storage = storageWith({ nom: 'Interrompu' });
      const { formDraft, entry } = mount({ storage });

      entry.set({ nom: 'Tapé avant de répondre' });
      TestBed.tick();
      vi.advanceTimersByTime(5000);
      expect(JSON.parse(storage.getItem(KEY)!).draft.nom).toBe('Interrompu');

      formDraft.ignore();
      entry.set({ nom: 'Tapé après avoir répondu' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);
      expect(JSON.parse(storage.getItem(KEY)!).draft.nom).toBe('Tapé après avoir répondu');
    });

    it('writes nothing for a form nobody touched', () => {
      const { storage } = mount();

      vi.advanceTimersByTime(5000);

      expect(storage!.length).toBe(0);
    });

    it('removes its draft once the entry is back to the opening', () => {
      const { entry, storage } = mount();
      entry.set({ nom: 'Autre' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      entry.set(OPENING);
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      expect(storage!.getItem(KEY)).toBeNull();
    });

    it('destroyed with a write pending, writes it at once: the accident it is for', () => {
      const { entry, storage } = mount();
      entry.set({ nom: 'Typed just before the navigation' });
      TestBed.tick();

      TestBed.resetTestingModule();

      expect(JSON.parse(storage!.getItem(KEY)!).draft.nom).toBe('Typed just before the navigation');
    });

    // The logout purges every draft, then navigates: neither the auto-save
    // timer nor the flush of the dialog that navigation destroys may put the
    // entry back.
    it('writes nothing once a logout purged the drafts, timer pending', () => {
      const { entry, storage } = mount({ type: 'animateur' });
      entry.set({ nom: 'Typed before the logout' });
      TestBed.tick();

      purgeAllDrafts(storage);
      vi.advanceTimersByTime(1000);
      entry.set({ nom: 'Typed after the logout' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      expect(storage!.length).toBe(0);
    });

    it('does not flush on destroy a write racing the logout purge', () => {
      const { entry, storage } = mount({ type: 'animateur' });
      entry.set({ nom: 'Typed just before the logout' });
      TestBed.tick();

      purgeAllDrafts(storage);
      TestBed.resetTestingModule();

      expect(storage!.length).toBe(0);
    });

    it('a form opened after the purge saves its draft as usual', () => {
      const storage = memoryStorage();
      purgeAllDrafts(storage);
      const { entry } = mount({ storage });

      entry.set({ nom: 'After a new login' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      expect(storage.getItem(KEY)).not.toBeNull();
    });

    it('an unavailable storage does not get in the way', () => {
      const { formDraft, entry } = mount({ storage: refusingStorage() });

      entry.set({ nom: 'Autre' });
      TestBed.tick();
      expect(() => vi.advanceTimersByTime(1000)).not.toThrow();
      expect(formDraft.found()).toBeNull();
      expect(formDraft.modified()).toBe(true);
    });
  });

  describe('a draft found at opening', () => {
    it('is offered, never imposed', () => {
      const { formDraft, entry } = mount({ storage: storageWith({ nom: 'Interrompu' }) });

      expect(formDraft.found()?.draft).toEqual({ nom: 'Interrompu' });
      expect(entry()).toEqual(OPENING);
    });

    it('« Reprendre » pours it into the form', () => {
      const { formDraft, entry } = mount({ storage: storageWith({ nom: 'Interrompu' }) });

      formDraft.resume();

      expect(entry()).toEqual({ nom: 'Interrompu' });
      expect(formDraft.found()).toBeNull();
    });

    it('« Ignorer » erases it', () => {
      const storage = storageWith({ nom: 'Interrompu' });
      const { formDraft } = mount({ storage });

      formDraft.ignore();

      expect(formDraft.found()).toBeNull();
      expect(storage.getItem(KEY)).toBeNull();
    });

    it('is not erased by a form closed untouched', async () => {
      const storage = storageWith({ nom: 'Interrompu' });
      const { formDraft } = mount({ storage });

      await formDraft.close();
      vi.advanceTimersByTime(5000);

      expect(storage.getItem(KEY)).not.toBeNull();
    });

    it('says when the record was modified since', () => {
      const older = mount({ storage: storageWith({ nom: 'Interrompu' }, '2026-06-01T08:00:00Z') });
      expect(older.formDraft.conflict()).toBe(true);

      const same = mount({ storage: storageWith({ nom: 'Interrompu' }) });
      expect(same.formDraft.conflict()).toBe(false);
    });

    it('is dropped without being offered when the form could not hold it', () => {
      const storage = storageWith({ somethingElse: true });
      const { formDraft } = mount({ storage });

      expect(formDraft.found()).toBeNull();
      expect(storage.getItem(KEY)).toBeNull();
    });
  });

  describe('protected close', () => {
    it('switches the implicit close of the dialog off', () => {
      const { ref } = mount();

      expect(ref.disableClose).toBe(true);
    });

    it('closes an unmodified form without a question', async () => {
      const { formDraft, ask, close } = mount();

      await formDraft.close();

      expect(ask).not.toHaveBeenCalled();
      expect(close).toHaveBeenCalledWith(false);
    });

    it('asks for a modified form; a confirmed discard erases the draft', async () => {
      const { formDraft, entry, storage, ask, close } = mount({ confirmed: true });
      entry.set({ nom: 'Autre' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      await formDraft.close();

      expect(ask).toHaveBeenCalledOnce();
      expect(close).toHaveBeenCalledWith(false);
      expect(storage!.getItem(KEY)).toBeNull();
    });

    it('« Continuer la saisie » keeps the form open and the draft in place', async () => {
      const { formDraft, entry, storage, close } = mount({ confirmed: false });
      entry.set({ nom: 'Autre' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      await formDraft.close();

      expect(close).not.toHaveBeenCalled();
      expect(storage!.getItem(KEY)).not.toBeNull();
    });

    it('Escape and the backdrop go through the same question', async () => {
      const { entry, ask, ref } = mount({ confirmed: false });
      entry.set({ nom: 'Autre' });
      TestBed.tick();

      ref.keys.next(new KeyboardEvent('keydown', { key: 'Escape' }));
      await Promise.resolve();
      ref.backdrop.next(new MouseEvent('click'));
      await Promise.resolve();

      expect(ask).toHaveBeenCalledTimes(2);
    });

    it('any other key closes nothing', () => {
      const { close, ask, ref } = mount();

      ref.keys.next(new KeyboardEvent('keydown', { key: 'Enter' }));

      expect(ask).not.toHaveBeenCalled();
      expect(close).not.toHaveBeenCalled();
    });

    it('once saved, the draft is erased and nothing is written any more', () => {
      const { formDraft, entry, storage } = mount();
      entry.set({ nom: 'Autre' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      formDraft.complete();
      entry.set({ nom: 'Encore' });
      TestBed.tick();
      vi.advanceTimersByTime(1000);

      expect(storage!.getItem(KEY)).toBeNull();
    });
  });

  describe('orphan drafts', () => {
    it('erases the draft of a record deleted meanwhile and says so, by its id', () => {
      const storage = memoryStorage();
      writeDraft(storage, draftKey('animateur', 'a7'), {}, null);
      writeDraft(storage, draftKey('animateur', 'a1'), {}, null);
      const notify = vi.fn();

      reportOrphanDrafts(
        { notify } as unknown as NotificationService,
        storage,
        'animateur',
        (id) => id === 'a1',
        (ids) => `L'animateur ${ids}`,
      );

      expect(storage.getItem(draftKey('animateur', 'a7'))).toBeNull();
      expect(storage.getItem(draftKey('animateur', 'a1'))).not.toBeNull();
      expect(notify).toHaveBeenCalledOnce();
      expect(notify.mock.calls[0][0].message).toContain("L'animateur a7");
    });

    it('says nothing while every record still exists', () => {
      const storage = memoryStorage();
      writeDraft(storage, draftKey('stand', 's1'), {}, null);
      const notify = vi.fn();

      reportOrphanDrafts(
        { notify } as unknown as NotificationService,
        storage,
        'stand',
        () => true,
        (ids) => ids,
      );

      expect(notify).not.toHaveBeenCalled();
    });
  });
});
