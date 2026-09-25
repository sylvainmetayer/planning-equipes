import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import {
  ActivatedRouteSnapshot,
  RedirectFunction,
  ResolveFn,
  Routes,
  TitleStrategy,
  provideRouter,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { routes } from './app.routes';
import { BRANDING } from './core/branding';
import { BrandingTitleStrategy } from './core/branding-title.strategy';

@Component({ template: '' })
class PageVide {}

/**
 * Every route of the tree, children included.
 *
 * <p>Does not descend into `loadChildren`: there is none today, and resolving
 * one would mean loading the modules. The day one arrives, the "whole tree"
 * guarantee stops holding without anything saying so.</p>
 */
function allRoutes(list: Routes): Routes {
  return list.flatMap((route) => [route, ...allRoutes(route.children ?? [])]);
}

/** Where the legacy route `path` redirects, given these query params. */
function redirectTarget(path: string, queryParams: Record<string, string>): string {
  const route = allRoutes(routes).find((candidate) => candidate.path === path);
  const redirectTo = route?.redirectTo as RedirectFunction;
  return redirectTo({ queryParams } as unknown as ActivatedRouteSnapshot) as string;
}

/**
 * Les titres de route étaient des chaînes en dur, moitié françaises moitié
 * anglaises, qu'aucun contrôle ne voyait : `check-i18n` ne lit que ce qui passe
 * par `$localize`. Ce n'est pas cosmétique — `admin-shell` annonce
 * `title.getTitle()` au lecteur d'écran à chaque navigation, donc la moitié des
 * écrans s'annonçait dans une langue que l'utilisateur ne lit pas.
 *
 * Deux choses sont vérifiées ici, et la seconde est celle qui manquait : que le
 * routeur résout bien un titre **fonction** et que la stratégie de marque le
 * reçoit. La spec de `BrandingTitleStrategy` simule `buildTitle`, donc rien ne
 * couvrait ce passage-là — un titre fonction qui ne serait jamais appelé
 * viderait chaque onglet sans faire échouer un seul test.
 */
describe('app.routes', () => {
  /**
   * Une route qui affiche un écran terminal doit porter un titre : sans lui
   * l'onglet ne montre que le nom du produit, et `admin-shell` n'annonce rien à
   * l'arrivée sur la page (son `annoncerNavigation` sort sur `if (titre)`).
   *
   * <p>Les coquilles de mise en page — le shell admin et celui de l'espace
   * animateur — en sont exemptes : elles portent des enfants, et ce sont eux qui
   * nomment l'écran.</p>
   */
  it('donne un titre à toute route qui affiche un écran terminal', () => {
    const untitled = allRoutes(routes)
      .filter((route) => route.component !== undefined || route.loadComponent !== undefined)
      .filter((route) => route.children === undefined || route.children.length === 0)
      .filter((route) => route.title === undefined)
      .map((route) => route.path ?? '(vide)');

    expect(untitled).toEqual([]);
  });

  it('donne un titre traduisible et non vide à chaque route qui en porte un', () => {
    const titrees = allRoutes(routes).filter((route) => route.title !== undefined);

    // Le compte exact plutôt qu'un plancher : un plancher laisse supprimer six
    // titres sans rien dire, et c'est ce chiffre-là que les descriptions de PR
    // annonçaient de travers.
    expect(titrees).toHaveLength(51);
    for (const route of titrees) {
      // Une fonction, et non une chaîne : c'est ce qui permet au titre de
      // passer par $localize sans être évalué au chargement du module, avant
      // que les traductions ne soient chargées.
      expect(typeof route.title, `route ${route.path}`).toBe('function');
      const resolu = (route.title as ResolveFn<string>)(undefined as never, undefined as never);
      expect(resolu, `route ${route.path}`).toBeTruthy();
    }
  });

  it("résout un titre fonction jusqu'au titre du document", async () => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'essai', title: () => 'Titre résolu', component: PageVide }]),
        {
          provide: BRANDING,
          useValue: { productName: 'Produit', organisation: '', logoUrl: '', accentColor: '' },
        },
        { provide: TitleStrategy, useClass: BrandingTitleStrategy },
      ],
    });

    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/essai');

    expect(TestBed.inject(Title).getTitle()).toBe('Titre résolu — Produit');
  });

  /**
   * The former addresses of the day's renderings redirect to the page, query
   * params included: the key renamed when the rendering owns another one, and
   * the value translated when it changed along with the key.
   */
  describe('les anciennes adresses des rendus de la journée', () => {
    it('garde les filtres et renomme la clé du rail', () => {
      expect(redirectTarget('rail-jour', { vue: 'libres', stand: 'S1' })).toBe(
        '/journee?vue=rail&lignes=libres&stand=S1',
      );
    });

    it('traduit la vue « sans relais » des pauses en son filtre, et le jour en date', () => {
      // `/pauses` always wrote an ISO date in `jour`: that is the bookmark the
      // redirect has to read back, not a day number.
      expect(redirectTarget('pauses', { vue: 'sans-relais', jour: '2026-08-02' })).toBe(
        '/journee?vue=pauses&relais=sans&date=2026-08-02',
      );
    });

    it('mène au calendrier sans paramètre superflu', () => {
      expect(redirectTarget('day-calendar', {})).toBe('/journee?vue=calendrier');
    });

    // The two addresses carrying the most params: the map's cursor, and the
    // créneau and stand pair of the bench.
    it('garde le jour et le curseur de la carte', () => {
      expect(redirectTarget('carte-jour', { jour: '2026-08-02', t: '540' })).toBe(
        '/journee?vue=carte&jour=2026-08-02&t=540',
      );
    });

    it("mène aux onglets du diagnostic avec l'état de chacun", () => {
      expect(
        redirectTarget('fragilite', { vue: 'COMPETENCES', filtre: 'CRITIQUES', q: 'Alice' }),
      ).toBe('/diagnostic?onglet=fragilite&vue=COMPETENCES&filtre=CRITIQUES&q=Alice');
      expect(redirectTarget('banc-de-touche', { creneau: '12', stand: 'S1' })).toBe(
        '/diagnostic?onglet=banc&creneau=12&stand=S1',
      );
    });
  });
});
