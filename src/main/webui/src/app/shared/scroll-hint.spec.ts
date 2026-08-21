import { Component, viewChild } from '@angular/core';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ScrollHint, resteDuContenuPlusBas } from './scroll-hint';

describe('resteDuContenuPlusBas', () => {
  it('annonce du contenu caché quand la page dépasse largement la fenêtre', () => {
    expect(resteDuContenuPlusBas({ scrollHeight: 2000, scrollTop: 0, clientHeight: 800 })).toBe(true);
  });

  it('se tait une fois le bas atteint', () => {
    expect(resteDuContenuPlusBas({ scrollHeight: 2000, scrollTop: 1200, clientHeight: 800 })).toBe(false);
  });

  it('ignore les quelques pixels d’une ombre ou d’un arrondi', () => {
    // Sous le seuil : la page est en pratique entièrement lue, l'indicateur
    // ne serait que du bruit dans le coin de l'écran.
    expect(resteDuContenuPlusBas({ scrollHeight: 1020, scrollTop: 0, clientHeight: 1000 })).toBe(false);
  });
});

/** Host driving the component the way the shell does: with a real scrollable element. */
@Component({
  imports: [ScrollHint],
  template: `
    <div #zone style="height: 100px; overflow: auto"><div style="height: 1000px"></div></div>
    <app-scroll-hint [conteneur]="zone" />
  `
})
class HoteTest {
  readonly hint = viewChild.required(ScrollHint);
}

describe('ScrollHint', () => {
  beforeEach(() => {
    // jsdom n'implémente pas ResizeObserver : le composant s'en sert pour
    // réagir au contenu qui grandit, pas pour la décision elle-même.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = vi.fn();
        disconnect = vi.fn();
      }
    );
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  it('affiche l’indicateur quand la zone défilante cache du contenu, et le retire au bas', async () => {
    const fixture = TestBed.createComponent(HoteTest);
    const zone = fixture.nativeElement.querySelector('div') as HTMLElement;
    // jsdom ne calcule aucune mise en page : on décrit la géométrie nous-mêmes.
    Object.defineProperty(zone, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(zone, 'clientHeight', { value: 100, configurable: true });
    zone.scrollTop = 0;

    await fixture.whenStable();
    zone.dispatchEvent(new Event('scroll'));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.scroll-hint')).not.toBeNull();

    zone.scrollTop = 900;
    zone.dispatchEvent(new Event('scroll'));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.scroll-hint')).toBeNull();
  });
});
