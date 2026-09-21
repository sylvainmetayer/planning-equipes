import { describe, expect, it } from 'vitest';

import { LIGHT_SURFACE, hexContrast, hexToOklch, oklchContrast } from './contrast';

/**
 * A wrong meter would pass green every test it serves. So what follows holds it
 * against figures that do not come from it: the ones the javadoc of
 * `core/branding.ts` has carried since issue #317, and the ones the RGAA audit
 * measured (#36, #40).
 */
describe('contrast', () => {
  describe('hexContrast', () => {
    // Both ends of the scale: nothing contrasts more than black on white, and
    // nothing less than a colour against itself.
    it('reads 21:1 between black and white', () => {
      expect(hexContrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
    });

    it('reads 1:1 for a colour on itself', () => {
      expect(hexContrast('#0098d7', '#0098d7')).toBeCloseTo(1, 5);
    });

    // The figure that motivated #40, and the one of the colour that fixes it.
    it('finds the 3.24:1 of the former brand bar', () => {
      expect(hexContrast('#ffffff', '#0098d7')).toBeCloseTo(3.24, 2);
    });

    it('finds the 4.54:1 of the corrected bar', () => {
      expect(hexContrast('#ffffff', '#007eb2')).toBeCloseTo(4.54, 2);
    });

    // `--app-toolbar-foreground` is written `#fff`, and a meter that reads the
    // three digits as `0x000fff` answers 1.84:1 for the very pair
    // `styles/branding.css` tells a deployment to measure again.
    it('reads a three-digit hex as its six-digit twin', () => {
      expect(hexContrast('#fff', '#007eb2')).toBeCloseTo(hexContrast('#ffffff', '#007eb2'), 10);
    });

    // A silently wrong figure is worse than none: the whole point of the meter
    // is that a spec can trust what it returns.
    it('refuses a value that is not a hex colour', () => {
      expect(() => hexContrast('rebeccapurple', '#ffffff')).toThrow(/not a hex colour/);
    });
  });

  describe('oklchContrast', () => {
    // The javadoc of `accentForBothSchemes` has announced 8.5:1 for this brand
    // ink on the light surface since issue #317; the trip through OKLCH and
    // back must not move it.
    it('finds the 8.5:1 of #8b1e3f on the light surface', () => {
      expect(oklchContrast(hexToOklch('#8b1e3f'), LIGHT_SURFACE)).toBeCloseTo(8.5, 1);
    });

    it('agrees with the plain hex measurement', () => {
      for (const colour of ['#8b1e3f', '#ffe066', '#007eb2', '#121316']) {
        expect(oklchContrast(hexToOklch(colour), LIGHT_SURFACE)).toBeCloseTo(
          hexContrast(colour, LIGHT_SURFACE),
          2,
        );
      }
    });

    // An out-of-gamut colour is brought back to what a screen really shows,
    // otherwise the measurement would be of a colour nobody ever sees.
    it('brings an impossible chroma back into the gamut', () => {
      const impossible = oklchContrast({ l: 0.53, c: 0.9, h: 143 }, LIGHT_SURFACE);

      expect(Number.isFinite(impossible)).toBe(true);
      expect(impossible).toBeGreaterThan(1);
    });
  });

  describe('hexToOklch', () => {
    it('reads the lightness and the hue of #8b1e3f', () => {
      const { l, c, h } = hexToOklch('#8b1e3f');

      expect(l).toBeCloseTo(0.426, 2);
      expect(c).toBeCloseTo(0.145, 2);
      expect(h).toBeCloseTo(8.3, 0);
    });

    it('reads a zero chroma for a grey', () => {
      expect(hexToOklch('#808080').c).toBeCloseTo(0, 3);
    });
  });
});
