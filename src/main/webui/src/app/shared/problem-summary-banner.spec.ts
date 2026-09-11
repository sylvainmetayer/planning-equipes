// The banner that interrupts the Solveur page when the dataset cannot produce a
// usable planning. It is deliberately silent unless something is *blocking*:
// showing it for warnings would train the user to ignore it, which is the only
// failure mode that matters for a banner of this kind.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { NiveauProbleme, Probleme } from '../core/problemes';
import { ProblemSummaryBanner } from './problem-summary-banner';

function probleme(id: string, niveau: NiveauProbleme): Probleme {
  return { id, niveau, source: 'CONTRAINTE', titre: id, message: '', details: [], liens: [] };
}

let fixture: ComponentFixture<ProblemSummaryBanner>;

function rendre(problemes: Probleme[]): HTMLElement {
  fixture.componentRef.setInput('problemes', problemes);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('ProblemSummaryBanner', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
    fixture = TestBed.createComponent(ProblemSummaryBanner);
  });

  it('shows nothing when there is no problem at all', () => {
    expect(rendre([]).querySelector('mat-card')).toBeNull();
  });

  it('stays silent on warnings and minor problems alone', () => {
    const racine = rendre([probleme('a', 'AVERTISSEMENT'), probleme('b', 'MINEUR')]);

    // Visible on the Problèmes page, not worth interrupting the nominal path.
    expect(racine.querySelector('mat-card')).toBeNull();
  });

  it('counts every severity once at least one problem is blocking', () => {
    const racine = rendre([
      probleme('a', 'BLOQUANT'),
      probleme('b', 'BLOQUANT'),
      probleme('c', 'AVERTISSEMENT'),
      probleme('d', 'MINEUR'),
    ]);

    expect(racine.querySelector('mat-card-content p')!.textContent!.trim()).toBe(
      '4 problème(s) : 2 bloquant(s), 1 avertissement(s), 1 mineur(s).',
    );
  });

  it('offers a way in to the page that lists them', () => {
    const racine = rendre([probleme('a', 'BLOQUANT')]);

    const lien = racine.querySelector('a') as HTMLAnchorElement;
    expect(lien.getAttribute('href')).toBe('/diagnostic');
    expect(lien.textContent!.trim()).toBe('Voir les problèmes');
  });

  it('disappears again once the blocking problems are gone', () => {
    rendre([probleme('a', 'BLOQUANT')]);

    // The banner is derived state: fixing the data must clear it without a reload.
    expect(rendre([probleme('c', 'AVERTISSEMENT')]).querySelector('mat-card')).toBeNull();
  });
});
