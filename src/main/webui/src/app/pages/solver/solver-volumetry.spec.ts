// The volumetry card reads the store and derives the one figure the template
// would otherwise get wrong: a fill ratio that must not divide by zero.

import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverVolumetry } from './solver-volumetry';

type CardInternals = {
  fillRatio: Signal<number | null>;
};

describe('SolverVolumetry', () => {
  const scale = signal({
    animateurCount: 0,
    posteCount: 0,
    contrainteAdHocCount: 0,
    hoursToFill: 0,
    hoursAvailable: 0,
  });
  const creneaux = signal<unknown[]>([]);
  let fixture: ComponentFixture<SolverVolumetry>;

  beforeEach(() => {
    scale.set({
      animateurCount: 0,
      posteCount: 0,
      contrainteAdHocCount: 0,
      hoursToFill: 0,
      hoursAvailable: 0,
    });
    creneaux.set([]);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ReferenceDataStore, useValue: { scale, creneaux } },
      ],
    });
  });

  function createCard(): CardInternals {
    fixture = TestBed.createComponent(SolverVolumetry);
    fixture.detectChanges();
    return fixture.componentInstance as unknown as CardInternals;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  it('says nothing about the fill ratio while no hour is offered', () => {
    scale.set({
      animateurCount: 10,
      posteCount: 40,
      contrainteAdHocCount: 0,
      hoursToFill: 120,
      hoursAvailable: 0,
    });
    const card = createCard();

    expect(card.fillRatio()).toBeNull();
    expect(text()).not.toContain('taux de remplissage');
  });

  it('puts hours to fill over hours available', () => {
    scale.set({
      animateurCount: 10,
      posteCount: 40,
      contrainteAdHocCount: 0,
      hoursToFill: 120,
      hoursAvailable: 160,
    });
    const card = createCard();

    expect(card.fillRatio()).toBeCloseTo(0.75);
    expect(text()).toContain('Taux de remplissage : 0.75');
  });

  it('prints no search space, and folds under its title', () => {
    scale.set({
      animateurCount: 100,
      posteCount: 1500,
      contrainteAdHocCount: 0,
      hoursToFill: 0,
      hoursAvailable: 0,
    });
    createCard();

    expect(text()).not.toContain('10^');
    expect(text()).not.toContain('espace de recherche');
    const details = (fixture.nativeElement as HTMLElement).querySelector('details');
    expect(details?.open).toBe(false);
    expect(details?.querySelector('summary')?.textContent).toContain('Volumétrie du problème');
  });

  it('follows the store as it changes', () => {
    scale.set({
      animateurCount: 10,
      posteCount: 40,
      contrainteAdHocCount: 3,
      hoursToFill: 0,
      hoursAvailable: 0,
    });
    creneaux.set([{}, {}]);
    createCard();
    const valeurs = (): string[] =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.staffing-stat-value'),
      ).map((each) => each.textContent!.trim());
    expect(valeurs().slice(0, 4)).toEqual(['10', '40', '2', '3']);

    creneaux.set([{}, {}, {}]);
    fixture.detectChanges();
    expect(valeurs()[2]).toBe('3');
  });
});
