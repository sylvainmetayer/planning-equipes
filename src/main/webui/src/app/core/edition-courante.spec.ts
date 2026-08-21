// The frontend counterpart of the backend's structural-isolation invariant:
// these 38 lines decide the `X-Edition-Id` header of every single request. A
// regression here writes silently into the wrong edition.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearStoredEditionId,
  editionScopedKey,
  getStoredEditionId,
  setStoredEditionIdAndReload
} from './edition-courante';

const STORAGE_KEY = 'planning-equipes.editionId';

describe('edition courante', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  describe('getStoredEditionId', () => {
    it('is null when nothing was ever picked, so the server keeps its default', () => {
      expect(getStoredEditionId()).toBeNull();
    });

    it('reads back exactly what was stored, without normalising it', () => {
      localStorage.setItem(STORAGE_KEY, 'ed-2026');
      expect(getStoredEditionId()).toBe('ed-2026');
    });

    it('is unaffected by other keys of the same browser', () => {
      localStorage.setItem('planning-equipes.locale', 'en');
      expect(getStoredEditionId()).toBeNull();
    });
  });

  describe('setStoredEditionIdAndReload', () => {
    it('stores the edition before reloading, or the reload would lose the choice', () => {
      const order: string[] = [];
      const reload = vi.fn(() => order.push(`reload:${localStorage.getItem(STORAGE_KEY)}`));
      vi.spyOn(window, 'location', 'get').mockReturnValue({
        ...window.location,
        reload
      } as unknown as Location);

      setStoredEditionIdAndReload('ed-2026');

      expect(localStorage.getItem(STORAGE_KEY)).toBe('ed-2026');
      expect(reload).toHaveBeenCalledTimes(1);
      // The write must already be visible when the page reloads.
      expect(order).toEqual(['reload:ed-2026']);
    });

    it('replaces a previously chosen edition rather than adding to it', () => {
      localStorage.setItem(STORAGE_KEY, 'ed-2025');
      vi.spyOn(window, 'location', 'get').mockReturnValue({
        ...window.location,
        reload: vi.fn()
      } as unknown as Location);

      setStoredEditionIdAndReload('ed-2026');

      expect(localStorage.getItem(STORAGE_KEY)).toBe('ed-2026');
    });
  });

  describe('clearStoredEditionId', () => {
    it('forgets the choice, so the next request falls back to the server default', () => {
      localStorage.setItem(STORAGE_KEY, 'ed-supprimee');

      clearStoredEditionId();

      expect(getStoredEditionId()).toBeNull();
    });

    it('is harmless when nothing was stored', () => {
      expect(() => clearStoredEditionId()).not.toThrow();
      expect(getStoredEditionId()).toBeNull();
    });
  });

  describe('editionScopedKey', () => {
    it('suffixes the key with the current edition, so two editions never share a log', () => {
      localStorage.setItem(STORAGE_KEY, 'ed-2026');
      expect(editionScopedKey('planning-equipes.notifications')).toBe(
        'planning-equipes.notifications.ed-2026'
      );
    });

    it('leaves the key bare when no edition is chosen', () => {
      expect(editionScopedKey('planning-equipes.notifications')).toBe('planning-equipes.notifications');
    });

    it('gives two different editions two different keys', () => {
      localStorage.setItem(STORAGE_KEY, 'ed-2025');
      const anPasse = editionScopedKey('planning-equipes.notifications');
      localStorage.setItem(STORAGE_KEY, 'ed-2026');
      const anCourant = editionScopedKey('planning-equipes.notifications');

      expect(anPasse).not.toBe(anCourant);
    });
  });
});
