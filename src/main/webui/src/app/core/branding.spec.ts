import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BRANDING_NEUTRE, accentForBothSchemes, appliquerBranding, loadBranding, slugMarque } from './branding';

/** Ce que `core/branding.ts` doit produire pour `#8b1e3f`, moitié claire intacte. */
const PAIRE_8B1E3F = 'light-dark(#8b1e3f, oklch(from #8b1e3f max(l, 0.78) min(c, 0.14) h))';

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
    vi.unstubAllGlobals();
    document.documentElement.style.removeProperty('--app-accent');
  });

  // jsdom n'expose aucun objet `CSS` : c'est le test qui décide ce que le
  // navigateur sait parser, et donc quelle branche est empruntée.
  function navigateurSachantDeriver(sait: boolean): void {
    vi.stubGlobal('CSS', { supports: () => sait });
  }

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
        accentColor: '#8b1e3f',
        mascotUrl: '',
        mascotIconUrl: ''
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

    // Le fond sombre est arrivé avec l'issue #317 : une encre de marque choisie
    // sur fond blanc doit désormais être posée en paire, sinon elle sert de
    // couleur de texte illisible sur la surface sombre.
    it('pose la couleur d\'accent en paire claire/sombre', () => {
      navigateurSachantDeriver(true);

      appliquerBranding({ ...BRANDING_NEUTRE, accentColor: '#8b1e3f' });

      expect(document.documentElement.style.getPropertyValue('--app-accent')).toBe(PAIRE_8B1E3F);
    });

    it('pose la couleur brute quand le navigateur ne sait pas la dériver', () => {
      navigateurSachantDeriver(false);

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

  describe('accentForBothSchemes', () => {
    // La moitié claire reste la couleur configurée au caractère près : le
    // thème clair ne doit pas bouger d'un pixel, seule la moitié sombre naît.
    it('garde la couleur configurée en clair et l\'éclaircit en sombre', () => {
      navigateurSachantDeriver(true);

      expect(accentForBothSchemes('#8b1e3f')).toBe(PAIRE_8B1E3F);
    });

    it('accepte n\'importe quelle notation de couleur CSS', () => {
      navigateurSachantDeriver(true);

      expect(accentForBothSchemes('rebeccapurple')).toBe(
        'light-dark(rebeccapurple, oklch(from rebeccapurple max(l, 0.78) min(c, 0.14) h))'
      );
    });

    // Firefox 120 à 127 connaît `light-dark()` mais pas la syntaxe relative :
    // la paire y serait invalide et emporterait aussi la moitié claire.
    it('retombe sur la couleur brute quand la paire n\'est pas parsable', () => {
      navigateurSachantDeriver(false);

      expect(accentForBothSchemes('#8b1e3f')).toBe('#8b1e3f');
    });

    it('retombe sur la couleur brute sans objet CSS du tout', () => {
      vi.stubGlobal('CSS', undefined);

      expect(accentForBothSchemes('#8b1e3f')).toBe('#8b1e3f');
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
