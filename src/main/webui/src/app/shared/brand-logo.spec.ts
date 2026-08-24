// The one component that decides whether a deployment shows a logo at all.
//
// Six templates used to hard-code the same image, so "deploy for another
// customer" meant editing six files and hoping none was missed. The interesting
// case is not the configured one — it is the *unconfigured* one: an instance
// with no mark of its own must render nothing, never the mark of the festival
// this code happened to be written for.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { BRANDING, BRANDING_NEUTRE } from '../core/branding';
import { Branding } from '../core/models';
import { BrandLogo } from './brand-logo';

function rendre(branding: Partial<Branding>) {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: BRANDING, useValue: { ...BRANDING_NEUTRE, ...branding } }
    ]
  });
  const fixture = TestBed.createComponent(BrandLogo);
  fixture.detectChanges();
  return fixture;
}

describe('BrandLogo', () => {
  it("ne rend aucune image quand aucun logo n'est configuré", () => {
    const fixture = rendre({ logoUrl: '' });

    // Not a broken image, not a placeholder: nothing.
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
  });

  it('rend le logo configuré', () => {
    const fixture = rendre({ logoUrl: 'https://exemple.test/logo.png' });

    const img = fixture.nativeElement.querySelector('img') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute('src')).toBe('https://exemple.test/logo.png');
  });

  it('donne au logo le nom du produit comme texte alternatif', () => {
    const fixture = rendre({ logoUrl: 'logo.png', productName: 'Festival du Jeu' });

    const img = fixture.nativeElement.querySelector('img') as HTMLImageElement;
    // A screen reader must announce the deployment, not "logo".
    expect(img.getAttribute('alt')).toBe('Festival du Jeu');
  });

  it('garde la classe que les barres d’outils stylent déjà', () => {
    const fixture = rendre({ logoUrl: 'logo.png' });

    // The component slots into existing layouts: losing this class would
    // move the logo in all six toolbars at once.
    expect(fixture.nativeElement.querySelector('img')?.classList.contains('app-logo')).toBe(true);
  });

  it("l'identité neutre par défaut n'affiche pas de logo", () => {
    // The token provides BRANDING_NEUTRE when nothing overrides it: that is
    // what a component rendered outside main.ts sees, tests included.
    const fixture = rendre({});

    expect(fixture.nativeElement.querySelector('img')).toBeNull();
  });
});
