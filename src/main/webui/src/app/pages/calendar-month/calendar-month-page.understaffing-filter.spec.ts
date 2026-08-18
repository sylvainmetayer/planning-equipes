// Regression: filtering the month calendar by animateur must not flag a
// fully-staffed stand as understaffed just because the filter hid its other
// animateurs from `entries` — reported live at
// /calendar?month=2026-07&animateur=A5, where every créneau was actually
// correctly staffed but nearly all of them showed the warning once filtered
// down to a single animateur. Needs TestBed (ActivatedRoute/Router), unlike
// calendar-month-page.spec.ts's pure buildAssignmentsByDate tests.

import { Signal, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningStateService } from '../../core/planning-state.service';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { CalendarMonthPage } from './calendar-month-page';

interface StandLineView {
  standId: string;
  entries: { label: string }[];
  totalAssigned: number;
  effectifRequis: number;
}

function labels(line: StandLineView): string[] {
  return line.entries.map((entry) => entry.label);
}

interface SlotEntryView {
  creneauId: number;
  stands: StandLineView[];
}

interface CellView {
  dateKey: string;
  understaffed: boolean;
}

/** Reaches the protected members the template binds to / user actions call. */
type PageInternals = {
  animateurFilter: Signal<string>;
  selectAnimateur(value: string): void;
  cells: Signal<CellView[]>;
  daySlots: Signal<SlotEntryView[]>;
  selectDay(cell: { dateKey: string; otherMonth: boolean }): void;
};

function stand(id: string, effectifMin: number): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin,
    effectifMax: effectifMin,
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
  return { id, jour: 1, date, heureDebut: '14:00', heureFin: '18:00', groupe: null };
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

describe('CalendarMonthPage — understaffing vs. animateur filter', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('does not flag a fully-staffed stand as understaffed once filtered down to one of its two animateurs', async () => {
    const s1 = stand('S1', 2);
    const c1 = creneau(1, '2026-07-17');
    const a1 = animateur('A1');
    const a2 = animateur('A2');
    const { fixture, page } = setUp([poste('p1', s1, c1, a1), poste('p2', s1, c1, a2)], {
      month: '2026-07',
      animateur: 'A1'
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const cell = page.cells().find((c) => c.dateKey === '2026-07-17');
    expect(cell?.understaffed).toBe(false);

    page.selectDay({ dateKey: '2026-07-17', otherMonth: false });
    fixture.detectChanges();
    await fixture.whenStable();

    const line = page.daySlots()[0].stands[0];
    expect(labels(line)).toEqual(['A1']); // display still narrows to the filtered animateur...
    expect(line.totalAssigned).toBe(2); // ...but the true headcount used for the flag does not.
  });

  it('still flags a genuinely understaffed stand while filtered to one of its assigned animateurs', async () => {
    const s1 = stand('S1', 3);
    const c1 = creneau(1, '2026-07-17');
    const a1 = animateur('A1');
    const a2 = animateur('A2');
    // Three seats generated, only two of them filled.
    const { fixture, page } = setUp([poste('p1', s1, c1, a1), poste('p2', s1, c1, a2), poste('p3', s1, c1, null)], {
      month: '2026-07',
      animateur: 'A1'
    });

    fixture.detectChanges();
    await fixture.whenStable();

    const cell = page.cells().find((c) => c.dateKey === '2026-07-17');
    expect(cell?.understaffed).toBe(true);

    page.selectDay({ dateKey: '2026-07-17', otherMonth: false });
    fixture.detectChanges();
    await fixture.whenStable();

    const line = page.daySlots()[0].stands[0];
    expect(labels(line)).toEqual(['A1']);
    expect(line.totalAssigned).toBe(2); // 2 assigned out of 3 seats: still understaffed, correctly.
    expect(line.effectifRequis).toBe(3);
  });

  it('stand-only filtering never needs correcting: entries.length already equals the true headcount', async () => {
    const s1 = stand('S1', 2);
    const s2 = stand('S2', 1);
    const c1 = creneau(1, '2026-07-17');
    const { fixture, page } = setUp(
      [poste('p1', s1, c1, animateur('A1')), poste('p2', s1, c1, animateur('A2')), poste('p3', s2, c1, animateur('A3'))],
      { month: '2026-07', stand: 'S1' }
    );

    fixture.detectChanges();
    await fixture.whenStable();

    page.selectDay({ dateKey: '2026-07-17', otherMonth: false });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.daySlots()[0].stands).toHaveLength(1); // S2's line is filtered out entirely, not miscounted.
    const line = page.daySlots()[0].stands[0];
    expect(line.standId).toBe('S1');
    expect(line.totalAssigned).toBe(2);
  });
});
