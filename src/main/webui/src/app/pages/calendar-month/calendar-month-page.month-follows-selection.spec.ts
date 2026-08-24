// Regression: picking an animateur (or a stand) whose affectations are all in
// another month left the grid on the displayed month — a planning solved for
// July, read in August, showed an empty grid above a non-empty day list.
// Needs TestBed (ActivatedRoute/Router), unlike calendar-month-page.spec.ts's
// pure buildAssignmentsByDate tests.

import { Signal, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningStateService } from '../../core/planning-state.service';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { CalendarMonthPage } from './calendar-month-page';

/** Reaches the protected members the template binds to / user actions call. */
type PageInternals = {
  month: Signal<Date>;
  selectAnimateur(value: string): void;
  selectStand(value: string): void;
  daySlots: Signal<{ creneauId: number }[]>;
};

function stand(id: string): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: []
  };
}

function animateur(id: string): Animateur {
  return { id, prenom: id, nom: '', dateNaissance: '2000-01-01', manager: false, competences: {}, souhaits: [], joursIndisponibles: [] };
}

function creneau(id: number, date: string): Creneau {
  return { id, jour: 1, date, heureDebut: '14:00', heureFin: '18:00' };
}

function poste(id: string, standRef: Stand, creneauRef: Creneau, animateurRef: Animateur | null): PosteAffectation {
  return { id, stand: standRef, creneau: creneauRef, animateur: animateurRef };
}

function setUp(postes: PosteAffectation[], queryParams: Record<string, string> = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: PlanningStateService, useValue: { loadForDisplay: vi.fn(async () => ({ animateurs: [], postes })) } },
      { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
    ]
  });
  const fixture = TestBed.createComponent(CalendarMonthPage);
  return { fixture, page: fixture.componentInstance as unknown as PageInternals };
}

const s1 = stand('S1');
const s2 = stand('S2');
const a1 = animateur('A1');
const a2 = animateur('A2');
const juillet10 = creneau(1, '2026-07-10');
const juillet12 = creneau(2, '2026-07-12');
const aout03 = creneau(3, '2026-08-03');

describe('CalendarMonthPage — the displayed month follows the selection', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('jumps to the first month holding an affectation of the selected animateur', async () => {
    const { fixture, page } = setUp(
      [poste('p1', s1, juillet10, a1), poste('p2', s1, aout03, a2)],
      { month: '2026-08' }
    );
    fixture.detectChanges();
    await fixture.whenStable();

    page.selectAnimateur('A1');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.month().getFullYear()).toBe(2026);
    expect(page.month().getMonth()).toBe(6); // July, 0-indexed
    expect(page.daySlots()).toHaveLength(1);
  });

  it('stays on the displayed month when it already holds an affectation of the selection', async () => {
    const { fixture, page } = setUp(
      [poste('p1', s1, juillet10, a1), poste('p2', s1, aout03, a1)],
      { month: '2026-08' }
    );
    fixture.detectChanges();
    await fixture.whenStable();

    page.selectAnimateur('A1');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.month().getMonth()).toBe(7); // August: the user is not moved under their feet
  });

  it('picks the first month of the selection, not merely an earlier one with other affectations', async () => {
    const { fixture, page } = setUp(
      [poste('p1', s1, juillet10, a2), poste('p2', s1, juillet12, a1), poste('p3', s1, aout03, a2)],
      { month: '2026-08' }
    );
    fixture.detectChanges();
    await fixture.whenStable();

    page.selectAnimateur('A1');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.month().getMonth()).toBe(6);
    expect(page.daySlots()[0].creneauId).toBe(2); // the 12th, A1's only day
  });

  it('applies the same rule to the stand filter', async () => {
    const { fixture, page } = setUp(
      [poste('p1', s1, juillet10, a1), poste('p2', s2, aout03, a2)],
      { month: '2026-08' }
    );
    fixture.detectChanges();
    await fixture.whenStable();

    page.selectStand('S1');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.month().getMonth()).toBe(6);
  });

  it('leaves the month alone when the selection has no affectation at all', async () => {
    const { fixture, page } = setUp([poste('p1', s1, juillet10, null)], { month: '2026-08' });
    fixture.detectChanges();
    await fixture.whenStable();

    page.selectAnimateur('A9');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.month().getMonth()).toBe(7);
  });
});
