// « Réglages avancés » is drawn only where something sits behind it; the
// screens' own conditions are held by `scripts/check-reglages-avances.js`.

import {
  ChangeDetectionStrategy,
  Component,
  provideZonelessChangeDetection,
  signal,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { reglagesAvances } from '../core/reglages-avances';
import { ReglagesAvancesToggle } from './reglages-avances';

@Component({
  imports: [ReglagesAvancesToggle],
  template: `<app-reglages-avances [etat]="etat" [utile]="utile()" />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Hote {
  readonly etat = reglagesAvances();
  readonly utile = signal(false);
}

describe('ReglagesAvancesToggle', () => {
  it('draws no button when the screen has nothing advanced to show', () => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(Hote);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button')).toBeNull();

    fixture.componentInstance.utile.set(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
  });
});
