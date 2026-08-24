import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BRANDING_NEUTRE, appliquerBranding, loadBranding, slugMarque } from './branding';

/**
 * La marque est lue avant le bootstrap : ce qui est vérifié ici, c'est qu'un
 * serveur muet ou une configuration vide ne laissent jamais l'application sans
 * identité — et surtout qu'elles ne lui en donnent pas une qui appartient à
 * quelqu'un d'autre.
 */
describe('branding', () => {
  // Le titre de l'onglet est posé hors d'Angular : on repart d'une valeur
  // connue pour ne pas dépendre de l'ordre des fichiers dans le même jsdom.
  beforeEach(() => {
    document.title = '';
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.documentElement.style.removeProperty('--app-accent');
  });

  describe('loadBranding', () => {
    function repond(body: unknown, ok = true): void {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({ ok, status: ok ? 200 : 500, json: async () => body })
      );
    }

    it('lit la marque servie par /api/branding', async () => {
      repond({
        productName: 'Planning Bénévoles',
        organisation: 'Ville hôte',
        logoUrl: 'logo.png',
        accentColor: '#8b1e3f'
      });

      await expect(loadBranding()).resolves.toEqual({
        productName: 'Planning Bénévoles',
        organisation: 'Ville hôte',
        logoUrl: 'logo.png',
        accentColor: '#8b1e3f'
      });
    });

    // Un nom vide produirait des titres commençant par un tiret ; un logo
    // absent doit rester absent, jamais être remplacé par un défaut.
    it('retombe sur un nom neutre mais laisse le logo vide', async () => {
      repond({ productName: '   ', organisation: '', logoUrl: '', accentColor: '' });

      const marque = await loadBranding();

      expect(marque.productName).toBe(BRANDING_NEUTRE.productName);
      expect(marque.logoUrl).toBe('');
    });

    it('reste utilisable quand le serveur ne répond pas', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => undefined);
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

      await expect(loadBranding()).resolves.toEqual(BRANDING_NEUTRE);
    });
  });

  describe('appliquerBranding', () => {
    it('nomme l\'onglet avec le nom du produit', () => {
      appliquerBranding({ ...BRANDING_NEUTRE, productName: 'Planning Bénévoles' });

      expect(document.title).toBe('Planning Bénévoles');
    });

    it('pose la couleur d\'accent en custom property', () => {
      appliquerBranding({ ...BRANDING_NEUTRE, accentColor: '#8b1e3f' });

      expect(document.documentElement.style.getPropertyValue('--app-accent')).toBe('#8b1e3f');
    });

    // Sans couleur configurée, l'accent compilé par mat.theme() doit rester en
    // place : écrire une chaîne vide écraserait la valeur par défaut.
    it('ne touche pas à l\'accent quand rien n\'est configuré', () => {
      appliquerBranding(BRANDING_NEUTRE);

      expect(document.documentElement.style.getPropertyValue('--app-accent')).toBe('');
    });
  });

  describe('slugMarque', () => {
    it('déplie les accents et les espaces', () => {
      expect(slugMarque('Planning Équipes')).toBe('planning-equipes');
    });

    it('retombe sur planning quand il ne reste rien', () => {
      expect(slugMarque('!!!')).toBe('planning');
    });
  });
});
