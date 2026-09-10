// Le champ de filtre rapide des tables de référence — et surtout la touche qui
// rend leur navigation clavier trouvable : Flèche bas y entre. Sans elle, la
// seule voie est la tabulation, dont le nombre d'arrêts change à chaque colonne
// triable ajoutée à l'en-tête.

import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { TableFilter } from './table-filter';

@Component({
  imports: [TableFilter],
  template: `<app-table-filter [(value)]="valeur" (enterTable)="entrees.set(entrees() + 1)" />`,
})
class Hote {
  readonly valeur = signal('');
  readonly entrees = signal(0);
}

describe('TableFilter', () => {
  let fixture: ComponentFixture<Hote>;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(Hote);
    await fixture.whenStable();
  });

  function champ(): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector('input')!;
  }

  function frapper(key: string): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    champ().dispatchEvent(event);
    return event;
  }

  it('demande l’entrée dans le tableau sur Flèche bas, et consomme la touche', async () => {
    const event = frapper('ArrowDown');
    await fixture.whenStable();

    expect(fixture.componentInstance.entrees()).toBe(1);
    expect(event.defaultPrevented).toBe(true);
  });

  it('laisse passer tout le reste', async () => {
    // « / », « ? » et Ctrl+K doivent atteindre l'écouteur global, et les flèches
    // horizontales rester le déplacement du curseur dans le texte.
    for (const key of ['ArrowUp', 'ArrowRight', 'ArrowLeft', '/', '?', 'Enter', 'a']) {
      expect(frapper(key).defaultPrevented, key).toBe(false);
    }
    await fixture.whenStable();

    expect(fixture.componentInstance.entrees()).toBe(0);
  });
});
