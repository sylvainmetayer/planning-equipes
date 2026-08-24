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
  accentColor: ''
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
  factory: () => BRANDING_NEUTRE
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
      accentColor: branding.accentColor?.trim() ?? ''
    };
  } catch (error) {
    console.error('Could not load the branding, falling back to a neutral identity.', error);
    return BRANDING_NEUTRE;
  }
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
    document.documentElement.style.setProperty('--app-accent', branding.accentColor);
  }
}

/** Filename slug of the deployment: `Planning Équipes` → `planning-equipes`. */
export function slugMarque(productName: string): string {
  const slug = productName
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug || 'planning';
}
