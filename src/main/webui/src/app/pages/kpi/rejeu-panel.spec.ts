// The Rejeu panel's own behaviour: the arithmetic is `rejeu.spec.ts`; what is
// pinned here is that the cursor moves by the page's rank — never on its own
// — and that « Lecture » never runs under prefers-reduced-motion.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { KpiHistoriqueEntry, PlanningKpi } from '../../core/models';
import { RejeuPanel } from './rejeu-panel';

function entry(
  id: number,
  creeLe: string,
  overrides: Partial<PlanningKpi> = {},
): KpiHistoriqueEntry {
  return {
    id,
    editionId: 'E26',
    editionNom: 'Édition 2026',
    creeLe,
    kpi: {
      score: '0hard/-10medium/-2soft',
      scoreHard: 0,
      scoreMedium: -10,
      scoreSoft: -2,
      postesTotal: 10,
      postesPourvus: 9,
      animateursAffectes: 4,
      standsDistincts: 2,
      creneauxDistincts: 3,
      heuresTotal: 30,
      heuresMoyenne: 7.5,
      heuresEcartType: 1,
      heuresMin: 6,
      heuresMax: 9,
      heuresIncompletes: false,
      modificationsManuelles: 0,
      tauxModificationsManuelles: 0,
      dureeSolveSecondes: 30,
      violationsParContrainte: {},
      scoreMediumHorsPlancher: null,
      plancherMedium: null,
      journeesSousConsigne: null,
      heuresFermeesParConsigne: null,
      ...overrides,
    },
  };
}

type PanelInternals = {
  current: Signal<number>;
  playing: Signal<boolean>;
  reducedMotion: boolean;
  step: (direction: -1 | 1) => void;
  togglePlay: () => void;
  onKeydown: (event: KeyboardEvent) => void;
  signed: (value: number | null, digits?: number) => string;
};

describe('RejeuPanel', () => {
  let fixture: ComponentFixture<RejeuPanel>;
  let emitted: number[];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(RejeuPanel);
    fixture.componentRef.setInput('entries', [
      entry(1, '2026-09-01T10:00:00Z'),
      entry(2, '2026-09-02T10:00:00Z'),
      entry(3, '2026-09-03T10:00:00Z'),
    ]);
    fixture.componentRef.setInput('editionId', 'E26');
    emitted = [];
    fixture.componentInstance.rankChange.subscribe((rank) => {
      emitted.push(rank);
      fixture.componentRef.setInput('rank', rank);
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function panel(): PanelInternals {
    return fixture.componentInstance as unknown as PanelInternals;
  }

  it('points at the latest solve until told otherwise', () => {
    expect(panel().current()).toBe(2);
  });

  it('steps with the buttons and the arrow keys, never past either end', () => {
    panel().step(1);
    expect(emitted).toEqual([2]);
    panel().onKeydown(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    panel().onKeydown(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    panel().onKeydown(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    expect(panel().current()).toBe(0);
  });

  it('plays one solve a second from the start, and stops by itself at the last', () => {
    vi.useFakeTimers();
    if (panel().reducedMotion) {
      return;
    }
    panel().togglePlay();
    expect(panel().current()).toBe(0);
    expect(panel().playing()).toBe(true);
    vi.advanceTimersByTime(1000);
    expect(panel().current()).toBe(1);
    vi.advanceTimersByTime(1000);
    expect(panel().current()).toBe(2);
    expect(panel().playing()).toBe(false);
  });

  it('never plays under prefers-reduced-motion', () => {
    Object.defineProperty(panel(), 'reducedMotion', { value: true });
    panel().togglePlay();
    expect(panel().playing()).toBe(false);
  });

  it("renders one small multiple per figure, the card, and an old row's band as unmeasured", async () => {
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('.rejeu-multiple')).toHaveLength(5);
    expect(root.textContent).toContain('Résolution 3 sur 3');
    expect(root.textContent).toContain('Couverture par jour non mesurée');
  });

  it('shades the band of a solve that measured its days', async () => {
    fixture.componentRef.setInput('entries', [
      entry(1, '2026-09-01T10:00:00Z', {
        couvertureParJour: {
          '2026-07-06': { postes: 4, pourvus: 2 },
          '2026-07-07': { postes: 2, pourvus: 2 },
        },
      }),
    ]);
    await fixture.whenStable();
    const cells = (fixture.nativeElement as HTMLElement).querySelectorAll('.rejeu-jour');
    expect(cells).toHaveLength(2);
    expect(cells[0].classList.contains('rejeu-jour-incomplet')).toBe(true);
    expect(cells[0].getAttribute('aria-label')).toContain('2 / 4');
  });

  it('writes a delta with a real minus sign', () => {
    expect(panel().signed(4)).toBe('+4');
    expect(panel().signed(-120)).toBe('−120');
    expect(panel().signed(0)).toBe('=');
    expect(panel().signed(null)).toBe('—');
  });
});
