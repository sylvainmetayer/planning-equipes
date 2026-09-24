import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getStoredLocale, intlLocale, setStoredLocaleAndReload } from './locale';

const STORAGE_KEY = 'planning-equipes.locale';

describe('locale', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  // The stored locale outlives the file: a test that leaves English behind
  // turns every date of the next spec file sharing the environment into
  // month/day.
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  describe('getStoredLocale', () => {
    it("retombe sur le français quand rien n'est stocké", () => {
      expect(getStoredLocale()).toBe('fr');
    });

    it("retourne l'anglais quand il a été stocké", () => {
      localStorage.setItem(STORAGE_KEY, 'en');
      expect(getStoredLocale()).toBe('en');
    });

    // Le stockage est une chaîne libre : une valeur qu'on ne gère pas (langue
    // retirée depuis, clé écrite à la main) ne doit pas laisser l'application
    // dans une locale inconnue.
    it('retombe sur le français pour une valeur non reconnue', () => {
      localStorage.setItem(STORAGE_KEY, 'de');
      expect(getStoredLocale()).toBe('fr');
    });
  });

  describe('intlLocale', () => {
    it('mappe fr vers fr-FR', () => {
      localStorage.setItem(STORAGE_KEY, 'fr');
      expect(intlLocale()).toBe('fr-FR');
    });

    it('mappe en vers en-US', () => {
      localStorage.setItem(STORAGE_KEY, 'en');
      expect(intlLocale()).toBe('en-US');
    });
  });

  describe('setStoredLocaleAndReload', () => {
    it('persiste la locale puis recharge la page', () => {
      const reload = vi.fn();
      // location.reload n'est pas appelable en test : on le remplace, en
      // vérifiant au passage que l'écriture précède bien le rechargement
      // (sinon le reload emporterait la valeur avant qu'elle soit lue).
      vi.spyOn(window, 'location', 'get').mockReturnValue({
        ...window.location,
        reload,
      } as unknown as Location);

      setStoredLocaleAndReload('en');

      expect(localStorage.getItem(STORAGE_KEY)).toBe('en');
      expect(reload).toHaveBeenCalledOnce();
    });
  });
});
