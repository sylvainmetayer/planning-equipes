// The identity this deployment wears: product name, logo, accent colour.
//
// Read from `/api/branding` once in `main.ts`, before the application
// bootstraps — the tab title and the toolbar logo decide what the very first
// frame looks like, and no screen can wait for a round trip to know what it is
// called. Same pattern as the i18n catalog and `/api/config` next to it.

import { InjectionToken } from '@angular/core';

import { Branding } from './models';

/**
 * What an instance looks like when the server could not be reached, or when the
 * operator configured nothing: a neutral name, no logo at all rather than
 * somebody else's, and the accent colour compiled into the Material theme.
 */
export const BRANDING_NEUTRE: Branding = {
  productName: 'Planning Équipes',
  organisation: '',
  logoUrl: '',
  accentColor: '',
  mascotUrl: '',
  mascotIconUrl: '',
  supportEmail: '',
};

/**
 * The `/api/branding` answer, provided at bootstrap so any screen can read it
 * synchronously — a toolbar cannot render half a logo while an HTTP call
 * settles.
 *
 * <p>It carries the neutral identity as its default factory, so a component
 * rendered outside `main.ts` — every unit test — reads a coherent brand
 * instead of failing on a missing provider.</p>
 */
export const BRANDING = new InjectionToken<Branding>('BRANDING', {
  providedIn: 'root',
  factory: () => BRANDING_NEUTRE,
});

/**
 * Fetched once before `bootstrapApplication()`. A failed fetch must not block
 * startup: the application then wears the neutral identity, which is
 * presentable and belongs to nobody.
 */
export async function loadBranding(): Promise<Branding> {
  try {
    const response = await fetch('/api/branding');
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const branding = (await response.json()) as Partial<Branding>;
    return {
      productName: branding.productName?.trim() || BRANDING_NEUTRE.productName,
      organisation: branding.organisation?.trim() ?? '',
      logoUrl: branding.logoUrl?.trim() ?? '',
      accentColor: branding.accentColor?.trim() ?? '',
      mascotUrl: branding.mascotUrl?.trim() ?? '',
      mascotIconUrl: branding.mascotIconUrl?.trim() ?? '',
      supportEmail: branding.supportEmail?.trim() ?? '',
    };
  } catch (error) {
    console.error('Could not load the branding, falling back to a neutral identity.', error);
    return BRANDING_NEUTRE;
  }
}

/**
 * Lightness floor and chroma ceiling of the dark half of the accent, in OKLCH.
 * 0.78 is the lowest lightness that keeps every hue above 8:1 on the dark
 * surface (`--mat-sys-surface` is `#121316` there); the chroma cap stops a
 * saturated brand from glaring once it has been lightened that far.
 */
const ACCENT_DARK_LIGHTNESS = 0.78;
const ACCENT_DARK_CHROMA = 0.14;

/**
 * Lightness ceiling of the light half, the mirror of the floor above.
 *
 * <p>0.53 is the highest lightness that keeps *every* hue and chroma at or
 * above 4.5:1 on the light surface (`--mat-sys-surface` is `#faf9fd` there);
 * the worst case is a saturated green around h=143, which still reads 4.69:1.
 * 0.55 was measured too and rejected: it bottoms out at 4.30:1.</p>
 */
const ACCENT_LIGHT_LIGHTNESS = 0.53;

/**
 * The accent as a `light-dark()` pair, each half held to a contrast floor: a
 * darkened twin of the configured colour on the light scheme, a lightened one
 * on the dark scheme.
 *
 * <p>`--app-accent` is a *text* colour on `--mat-sys-surface` in a dozen
 * partials (help titles, detail labels, the active drawer link), and the focus
 * ring of five grids. An operator picks `BRANDING_ACCENT_COLOR` by looking at
 * the light surface, so a deep brand ink — `#8b1e3f` reads 8.5:1 on `#faf9fd`
 * — collapses to 2.1:1 once the dark scheme paints `#121316` under it. Raising
 * the lightness in OKLCH keeps the hue, and therefore the identity, while
 * restoring the contrast.</p>
 *
 * <p>The light half used to be passed through untouched, on the reasoning that
 * an operator picks the colour by looking at that very surface. True in
 * general, false the moment they do not: a pastel brand — a yellow, a light
 * pink — went under 4.5:1 everywhere `--app-accent` inks text, and under the
 * 3:1 of criterion 3.3 as a focus ring, where falling through makes keyboard
 * navigation of the grids invisible. The RGAA audit (issue #40) measured it;
 * the ceiling below is the mirror of the floor above, so neither scheme is the
 * one nobody checked.</p>
 *
 * <p>The pair is only kept if the browser can parse it: relative colour syntax
 * shipped in Firefox 128 while `light-dark()` shipped in 120, so a handful of
 * versions would read the whole value as invalid. They keep the flat colour,
 * which is exactly what they showed before the theme switch existed. A
 * malformed `BRANDING_ACCENT_COLOR` falls back the same way.</p>
 */
export function accentForBothSchemes(accent: string): string {
  const light = `oklch(from ${accent} min(l, ${ACCENT_LIGHT_LIGHTNESS}) c h)`;
  const dark = `oklch(from ${accent} max(l, ${ACCENT_DARK_LIGHTNESS}) min(c, ${ACCENT_DARK_CHROMA}) h)`;
  const pair = `light-dark(${light}, ${dark})`;
  const supports = typeof CSS !== 'undefined' && typeof CSS.supports === 'function';
  return supports && CSS.supports('color', pair) ? pair : accent;
}

/**
 * Applies what has to be applied outside Angular: the document title — so the
 * tab is right before the first route resolves — and the accent colour, set as
 * a custom property on the root element.
 *
 * <p>Only `--app-accent` moves: this application's own stylesheets read it, but
 * Angular Material's tonal palette is compiled into the bundle by
 * `mat.theme()`. Recolouring the Material components themselves needs a
 * rebuild of `src/material-theme.scss`, not a variable — see
 * `docs/architecture.md`.</p>
 */
export function appliquerBranding(branding: Branding): void {
  document.title = branding.productName;
  if (branding.accentColor) {
    document.documentElement.style.setProperty(
      '--app-accent',
      accentForBothSchemes(branding.accentColor),
    );
  }
}

/** Filename slug of the deployment: `Planning Équipes` → `planning-equipes`. */
export function slugMarque(productName: string): string {
  const slug = productName
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, ''); // runs already collapsed to one dash
  return slug || 'planning';
}
