import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BRANDING } from './branding';
import { BrandingTitleStrategy } from './branding-title.strategy';

/**
 * Le suffixe du titre était recopié dans trente-cinq routes : le renommer
 * demandait trente-cinq lignes de diff, et une marque blanche était
 * impossible. Ce qui est vérifié ici, c'est que le nom du produit vient
 * désormais de la configuration — et que le séparateur reste celui que le
 * shell découpe pour annoncer la page aux lecteurs d'écran.
 */
describe('BrandingTitleStrategy', () => {
  function strategie(productName: string): BrandingTitleStrategy {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: BRANDING, useValue: { productName, organisation: '', logoUrl: '', accentColor: '' } }
      ]
    });
    return TestBed.inject(BrandingTitleStrategy);
  }

  /**
   * `buildTitle` est la part du framework — il déplie l'arbre de routes pour
   * trouver le titre le plus profond. Ce qui est testé ici est la composition
   * qui vient après, donc on lui donne directement sa réponse.
   */
  function titreDePage(strategy: BrandingTitleStrategy, page: string | undefined): void {
    vi.spyOn(strategy, 'buildTitle').mockReturnValue(page);
    strategy.updateTitle({} as RouterStateSnapshot);
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('accole le nom du produit au titre de la page', () => {
    const strategy = strategie('Planning Bénévoles');

    titreDePage(strategy, 'Stands');

    expect(TestBed.inject(Title).getTitle()).toBe('Stands — Planning Bénévoles');
  });

  // Une route sans titre (les redirections héritées) ne doit pas produire un
  // onglet ouvrant sur un tiret orphelin.
  it('affiche le seul nom du produit quand la route n\'a pas de titre', () => {
    const strategy = strategie('Planning Bénévoles');

    titreDePage(strategy, undefined);

    expect(TestBed.inject(Title).getTitle()).toBe('Planning Bénévoles');
  });
});
