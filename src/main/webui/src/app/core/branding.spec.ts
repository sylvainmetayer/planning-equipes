import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BRANDING_NEUTRE,
  accentForBothSchemes,
  appliquerBranding,
  loadBranding,
  slugMarque,
} from './branding';
import { DARK_SURFACE, LIGHT_SURFACE, hexToOklch, oklchContrast } from './testing/contrast';

/** What `core/branding.ts` must produce for `#8b1e3f`: one clamped half per scheme. */
const PAIR_8B1E3F =
  'light-dark(oklch(from #8b1e3f min(l, 0.53) c h), oklch(from #8b1e3f max(l, 0.78) min(c, 0.14) h))';

// jsdom exposes no `CSS` object: the test decides what the browser can parse,
// and therefore which branch is taken.
function stubCssSupports(supported: boolean): void {
  vi.stubGlobal('CSS', { supports: () => supported });
}

function stubFetchResponse(body: unknown, ok = true): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok, status: ok ? 200 : 500, json: async () => body }),
  );
}

/**
 * The two clamps the pair declares, read back out of the string the code
 * produces rather than copied here: what is measured below is the policy
 * actually served to the browser, not a twin constant somebody would one
 * day forget to keep in step.
 */
function clamps(pair: string): { light: number; dark: number; chroma: number } {
  const light = /min\(l, ([\d.]+)\)/.exec(pair);
  const dark = /max\(l, ([\d.]+)\) min\(c, ([\d.]+)\)/.exec(pair);
  if (!light || !dark) throw new Error(`pair cannot be read: ${pair}`);
  return { light: +light[1], dark: +dark[1], chroma: +dark[2] };
}

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

  describe('loadBranding', () => {
    it('lit la marque servie par /api/branding', async () => {
      stubFetchResponse({
        productName: 'Planning des animateurs',
        organisation: 'Ville hôte',
        logoUrl: 'logo.png',
        accentColor: '#8b1e3f',
      });

      await expect(loadBranding()).resolves.toEqual({
        productName: 'Planning des animateurs',
        organisation: 'Ville hôte',
        logoUrl: 'logo.png',
        accentColor: '#8b1e3f',
        mascotUrl: '',
        mascotIconUrl: '',
        supportEmail: '',
      });
    });

    // Un nom vide produirait des titres commençant par un tiret ; un logo
    // absent doit rester absent, jamais être remplacé par un défaut.
    it('retombe sur un nom neutre mais laisse le logo vide', async () => {
      stubFetchResponse({ productName: '   ', organisation: '', logoUrl: '', accentColor: '' });

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
    it("nomme l'onglet avec le nom du produit", () => {
      appliquerBranding({ ...BRANDING_NEUTRE, productName: 'Planning des animateurs' });

      expect(document.title).toBe('Planning des animateurs');
    });

    // Le fond sombre est arrivé avec l'issue #317 : une encre de marque choisie
    // sur fond blanc doit désormais être posée en paire, sinon elle sert de
    // couleur de texte illisible sur la surface sombre.
    it("pose la couleur d'accent en paire claire/sombre", () => {
      stubCssSupports(true);

      appliquerBranding({ ...BRANDING_NEUTRE, accentColor: '#8b1e3f' });

      expect(document.documentElement.style.getPropertyValue('--app-accent')).toBe(PAIR_8B1E3F);
    });

    it('pose la couleur brute quand le navigateur ne sait pas la dériver', () => {
      stubCssSupports(false);

      appliquerBranding({ ...BRANDING_NEUTRE, accentColor: '#8b1e3f' });

      expect(document.documentElement.style.getPropertyValue('--app-accent')).toBe('#8b1e3f');
    });

    // Sans couleur configurée, l'accent compilé par mat.theme() doit rester en
    // place : écrire une chaîne vide écraserait la valeur par défaut.
    it("ne touche pas à l'accent quand rien n'est configuré", () => {
      appliquerBranding(BRANDING_NEUTRE);

      expect(document.documentElement.style.getPropertyValue('--app-accent')).toBe('');
    });
  });

  describe('accentForBothSchemes', () => {
    it('borne la couleur configurée dans chaque schéma', () => {
      stubCssSupports(true);

      expect(accentForBothSchemes('#8b1e3f')).toBe(PAIR_8B1E3F);
    });

    it("accepte n'importe quelle notation de couleur CSS", () => {
      stubCssSupports(true);

      expect(accentForBothSchemes('rebeccapurple')).toBe(
        'light-dark(oklch(from rebeccapurple min(l, 0.53) c h), oklch(from rebeccapurple max(l, 0.78) min(c, 0.14) h))',
      );
    });

    // The heart of issue #40: the light half used to be passed through as it
    // was, on the reasoning that an operator picks the accent while looking at
    // the light surface. A brand yellow or a pale pink therefore went under
    // 4.5:1 everywhere `--app-accent` inks text, and under 3:1 as a focus
    // ring, where the keyboard of the grids becomes invisible.
    describe('bornes de contraste', () => {
      /** Deliberately bad pastel accents, of the kind an operator configures. */
      const PASTELS = ['#ffe066', '#f8b3c5', '#b8e986', '#9ad5ff', '#fff2a8'];

      it.each(PASTELS)('tient 4,5:1 sur la surface claire pour %s', (accent) => {
        stubCssSupports(true);
        const { light } = clamps(accentForBothSchemes(accent));
        const configured = hexToOklch(accent);

        const measured = oklchContrast(
          { ...configured, l: Math.min(configured.l, light) },
          LIGHT_SURFACE,
        );

        expect(measured).toBeGreaterThanOrEqual(4.5);
      });

      it.each(PASTELS)('tient 4,5:1 sur la surface sombre pour %s', (accent) => {
        stubCssSupports(true);
        const { dark, chroma } = clamps(accentForBothSchemes(accent));
        const configured = hexToOklch(accent);

        const measured = oklchContrast(
          {
            l: Math.max(configured.l, dark),
            c: Math.min(configured.c, chroma),
            h: configured.h,
          },
          DARK_SURFACE,
        );

        expect(measured).toBeGreaterThanOrEqual(4.5);
      });

      // A pastel is only one case: the clamp has to hold for every hue and
      // every saturation, otherwise the next brand falls back into the hole.
      // The worst case measured is a saturated green around h=143.
      it('tient 4,5:1 sur la surface claire pour toute teinte', () => {
        stubCssSupports(true);
        const { light } = clamps(accentForBothSchemes('#8b1e3f'));

        let worst = Infinity;
        for (let h = 0; h < 360; h += 3) {
          for (let c = 0; c <= 0.4; c += 0.02) {
            worst = Math.min(worst, oklchContrast({ l: light, c, h }, LIGHT_SURFACE));
          }
        }

        expect(worst).toBeGreaterThanOrEqual(4.5);
      });
    });

    // Firefox 120 à 127 connaît `light-dark()` mais pas la syntaxe relative :
    // la paire y serait invalide et emporterait aussi la moitié claire.
    it("retombe sur la couleur brute quand la paire n'est pas parsable", () => {
      stubCssSupports(false);

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
