// The volumetry card reads the store and derives two figures the template
// would otherwise get wrong: a fill ratio that must not divide by zero, and a
// problem scale that is a logarithm, not a product.

import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverVolumetry } from './solver-volumetry';

type CardInternals = {
  fillRatio: Signal<number | null>;
  problemScale: Signal<number>;
};

describe('SolverVolumetry', () => {
  const scale = signal({
    animateurCount: 0,
    posteCount: 0,
    contrainteAdHocCount: 0,
    hoursToFill: 0,
    hoursAvailable: 0
  });
  const creneaux = signal<unknown[]>([]);
  let fixture: ComponentFixture<SolverVolumetry>;

  beforeEach(() => {
    scale.set({ animateurCount: 0, posteCount: 0, contrainteAdHocCount: 0, hoursToFill: 0, hoursAvailable: 0 });
    creneaux.set([]);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ReferenceDataStore, useValue: { scale, creneaux } }]
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
    scale.set({ animateurCount: 10, posteCount: 40, contrainteAdHocCount: 0, hoursToFill: 120, hoursAvailable: 0 });
    const card = createCard();

    expect(card.fillRatio()).toBeNull();
    expect(text()).not.toContain('taux de remplissage');
  });

  it('puts hours to fill over hours available', () => {
    scale.set({ animateurCount: 10, posteCount: 40, contrainteAdHocCount: 0, hoursToFill: 120, hoursAvailable: 160 });
    const card = createCard();

    expect(card.fillRatio()).toBeCloseTo(0.75);
    expect(text()).toContain('taux de remplissage de 0.75');
  });

  it('gives the problem scale as postes times log10 of the animateurs', () => {
    scale.set({ animateurCount: 100, posteCount: 1500, contrainteAdHocCount: 0, hoursToFill: 0, hoursAvailable: 0 });
    const card = createCard();

    expect(card.problemScale()).toBe(3000);
    expect(text()).toContain('10^3000');
  });

  it('shows no scale at all with a single animateur or no poste', () => {
    scale.set({ animateurCount: 1, posteCount: 1500, contrainteAdHocCount: 0, hoursToFill: 0, hoursAvailable: 0 });
    expect(createCard().problemScale()).toBe(0);

    scale.set({ animateurCount: 100, posteCount: 0, contrainteAdHocCount: 0, hoursToFill: 0, hoursAvailable: 0 });
    expect(createCard().problemScale()).toBe(0);
  });

  it('follows the store as it changes', () => {
    scale.set({ animateurCount: 10, posteCount: 40, contrainteAdHocCount: 3, hoursToFill: 0, hoursAvailable: 0 });
    creneaux.set([{}, {}]);
    createCard();
    expect(text()).toContain('10 animateurs sur 40 postes (2 créneaux), sous 3 ajustements');

    creneaux.set([{}, {}, {}]);
    fixture.detectChanges();
    expect(text()).toContain('(3 créneaux)');
  });
});
