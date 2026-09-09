// Le couplage que cette directive coupe est invisible dans le DOM écrit : il
// naît du calcul du nom accessible, qui descend dans les enfants du bouton de
// tri généré par Material. Le test le reproduit donc à l'identique — un
// en-tête triable contenant un bouton d'aide à l'`aria-label` bavard — et
// calcule ce nom, plutôt que d'observer un attribut.

import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSortModule } from '@angular/material/sort';
import { describe, expect, it } from 'vitest';
import { SortHeaderName } from './sort-header-name';

const AIDE = "Ce que l'animateur a répondu. Confirmé, relancé, silencieux.";

/**
 * Accessible name of an element, restricted to what this test needs: an
 * explicit `aria-labelledby`, an explicit `aria-label`, or — failing both —
 * the name computed from the content, which is where the regression lived.
 */
function nomAccessible(element: Element): string {
  const referenceId = element.getAttribute('aria-labelledby');
  if (referenceId) {
    const target = element.ownerDocument.getElementById(referenceId);
    return (target?.textContent ?? '').trim();
  }
  const explicite = element.getAttribute('aria-label');
  if (explicite) {
    return explicite.trim();
  }
  return Array.from(element.childNodes)
    .map((noeud) =>
      noeud.nodeType === Node.ELEMENT_NODE
        ? nomAccessible(noeud as Element)
        : (noeud.textContent ?? '')
    )
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

@Component({
  imports: [MatSortModule, SortHeaderName],
  template: `
    <table matSort>
      <tr>
        <th id="avec" mat-sort-header="confirmation" [appSortHeaderName]="titre">
          <span #titre>Accusé de réception</span>
          <button type="button" class="column-help" [attr.aria-label]="aide">?</button>
        </th>
        <th id="sans" mat-sort-header="autre">
          <span>Accusé de réception</span>
          <button type="button" class="column-help" [attr.aria-label]="aide">?</button>
        </th>
      </tr>
    </table>
  `
})
class HoteTest {
  readonly aide = AIDE;
}

describe('SortHeaderName', () => {
  function rendre(): HTMLElement {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(HoteTest);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function boutonDeTri(racine: HTMLElement, entete: string): Element {
    const bouton = racine.querySelector(`#${entete} .mat-sort-header-container`);
    // Material nomme cette classe ; si une montée de version la renomme, la
    // directive ne fait plus rien et le défaut revient en silence.
    expect(bouton, 'conteneur de tri de Material').not.toBeNull();
    return bouton as Element;
  }

  it("nomme l'en-tête par son titre, sans l'aide qu'il contient", () => {
    const racine = rendre();
    const nom = nomAccessible(boutonDeTri(racine, 'avec'));

    expect(nom).toBe('Accusé de réception');
    expect(nom).not.toContain("Ce que l'animateur a répondu");
  });

  it('laisse au bouton d’aide son nom entier, celui qui explique la colonne', () => {
    const racine = rendre();
    const aide = racine.querySelector('#avec .column-help') as HTMLElement;

    expect(nomAccessible(aide)).toBe(AIDE);
  });

  it('vaut bien quelque chose : sans elle, le tri absorbe l’aide', () => {
    // Le témoin. Il fige le comportement de Material qui a causé la
    // régression : le jour où ce nom cesserait d'absorber l'aide, la
    // directive serait devenue inutile — et ce test le dirait.
    const racine = rendre();

    expect(nomAccessible(boutonDeTri(racine, 'sans'))).toContain("Ce que l'animateur a répondu");
  });

  it('donne un identifiant au titre qui n’en a pas', () => {
    const racine = rendre();
    const reference = boutonDeTri(racine, 'avec').getAttribute('aria-labelledby');

    expect(reference).toBeTruthy();
    expect(racine.querySelector(`#${reference}`)?.textContent?.trim()).toBe('Accusé de réception');
  });
});
