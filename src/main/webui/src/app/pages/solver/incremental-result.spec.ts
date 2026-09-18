// What the last incremental re-solve reopened, and who came out of it with a
// different planning. Display only, so the tests read the rendered table.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ChangementAffectation, StatistiquesIncremental } from '../../core/models';
import { IncrementalResult } from './incremental-result';

function statistiques(overrides: Partial<StatistiquesIncremental> = {}): StatistiquesIncremental {
  return {
    postesTotal: 200,
    postesFiges: 180,
    postesLiberes: 20,
    postesLiberesManuellement: 2,
    postesNouveaux: 0,
    postesPasses: 0,
    ...overrides,
  };
}

function changement(overrides: Partial<ChangementAffectation> = {}): ChangementAffectation {
  return {
    standId: 'tir',
    standNom: 'Tir',
    creneauId: 1,
    date: '2026-08-01',
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    avant: ['Alice'],
    apres: ['Bob'],
    ...overrides,
  };
}

describe('IncrementalResult', () => {
  let fixture: ComponentFixture<IncrementalResult>;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  function render(
    changements: ChangementAffectation[],
    stats: StatistiquesIncremental = statistiques(),
  ): HTMLElement {
    fixture = TestBed.createComponent(IncrementalResult);
    fixture.componentRef.setInput('stats', stats);
    fixture.componentRef.setInput('changements', changements);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function rows(root: HTMLElement): string[] {
    return Array.from(root.querySelectorAll('tr[mat-row]')).map((row) =>
      row.textContent!.replace(/\s+/g, ' ').trim(),
    );
  }

  it('frames the diff with four counts, the past seats only once the event is under way', () => {
    const values = (root: HTMLElement) =>
      Array.from(root.querySelectorAll('.staffing-stat-value')).map((value) =>
        value.textContent!.trim(),
      );
    expect(values(render([]))).toEqual(['180', '20', '2', '0']);
    expect(values(render([], statistiques({ postesPasses: 40 })))).toEqual([
      '180',
      '20',
      '2',
      '0',
      '40',
    ]);
    // A result persisted before the rule carries no count at all.
    const { postesPasses: _ignored, ...ancien } = statistiques();
    expect(values(render([], ancien as StatistiquesIncremental))).toEqual(['180', '20', '2', '0']);
  });

  it('warns about the past seats left empty, and only then', () => {
    expect(
      render([], statistiques({ postesPasses: 40 })).querySelector('.solver-passes-vides'),
    ).toBeNull();

    const root = render([], statistiques({ postesPasses: 40, postesPassesVides: 5 }));
    expect(root.textContent!.replace(/\s+/g, ' ')).toContain('5 sièges passés sont restés vides.');
  });

  it('says so when no crew changed, and draws no table', () => {
    const root = render([]);

    expect(root.textContent).toContain("Aucune équipe n'a changé");
    expect(root.querySelector('table')).toBeNull();
  });

  it('lists each moved crew with its créneau, seconds dropped from the hours', () => {
    const root = render([
      changement(),
      changement({ standNom: 'Quilles', avant: ['Chloé', 'Dan'], apres: ['Dan'] }),
    ]);

    const lines = rows(root);
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('10:00 – 12:00');
    expect(lines[0]).toContain('Tir');
    expect(lines[0]).toContain('Alice');
    expect(lines[0]).toContain('Bob');
    expect(lines[1]).toContain('Chloé, Dan');
  });

  it('reads an empty crew as a hole, not as nothing', () => {
    const root = render([changement({ avant: [], apres: ['Bob'] })]);

    expect(rows(root)[0]).toContain('(personne)');
  });

  it('shows the hours alone when the créneau carries no date', () => {
    const root = render([changement({ date: null as unknown as string })]);

    expect(rows(root)[0]).toMatch(/^10:00 – 12:00/);
  });
});
