import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BRANDING } from './branding';
import { BrandingTitleStrategy } from './branding-title.strategy';

function buildStrategy(productName: string): BrandingTitleStrategy {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: BRANDING,
        useValue: { productName, organisation: '', logoUrl: '', accentColor: '' },
      },
    ],
  });
  return TestBed.inject(BrandingTitleStrategy);
}

/**
 * `buildTitle` is the framework's part — it walks the route tree to find the
 * deepest title. What is tested here is the composition that comes after, so
 * it is handed its answer directly.
 */
function setPageTitle(strategy: BrandingTitleStrategy, page: string | undefined): void {
  vi.spyOn(strategy, 'buildTitle').mockReturnValue(page);
  strategy.updateTitle({} as RouterStateSnapshot);
}

/**
 * Le suffixe du titre était recopié dans trente-cinq routes : le renommer
 * demandait trente-cinq lignes de diff, et une marque blanche était
 * impossible. Ce qui est vérifié ici, c'est que le nom du produit vient
 * désormais de la configuration — et que le séparateur reste celui que le
 * shell découpe pour annoncer la page aux lecteurs d'écran.
 */
describe('BrandingTitleStrategy', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('accole le nom du produit au titre de la page', () => {
    const strategy = buildStrategy('Planning des animateurs');

    setPageTitle(strategy, 'Stands');

    expect(TestBed.inject(Title).getTitle()).toBe('Stands — Planning des animateurs');
  });

  // Une route sans titre (les redirections héritées) ne doit pas produire un
  // onglet ouvrant sur un tiret orphelin.
  it("affiche le seul nom du produit quand la route n'a pas de titre", () => {
    const strategy = buildStrategy('Planning des animateurs');

    setPageTitle(strategy, undefined);

    expect(TestBed.inject(Title).getTitle()).toBe('Planning des animateurs');
  });
});
