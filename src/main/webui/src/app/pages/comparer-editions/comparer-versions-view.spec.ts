// The plans view of « Comparer deux versions »: the pair read from the address,
// the older side as the reference, and a pair of the same version refused.

import { Location } from '@angular/common';
import { Component, input, output, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanSnapshot } from '../../core/models';
import { ComparaisonPanel } from '../versions/comparaison-panel';
import { ComparerVersionsView } from './comparer-versions-view';

@Component({ selector: 'app-comparaison-panel', template: '' })
class FakePanel {
  readonly base = input.required<string>();
  readonly variante = input.required<string>();
  readonly snapshots = input<readonly PlanSnapshot[]>([]);
  readonly closed = output<void>();
}

function version(id: number, creeLe: string, editionNom: string | null = null): PlanSnapshot {
  return {
    id,
    libelle: `Version ${id}`,
    automatique: false,
    origine: 'MANUELLE',
    score: null,
    nombreAffectations: 10,
    creeLe,
    editionId: 'E1',
    editionNom,
    kpi: null,
    referenceModifieLe: null,
    perime: false,
    publieLe: null,
  };
}

describe('ComparerVersionsView', () => {
  const api = { comparableSnapshots: vi.fn() };

  beforeEach(() => {
    api.comparableSnapshots.mockResolvedValue([
      version(7, '2026-08-01T10:00:00Z'),
      version(8, '2026-08-02T10:00:00Z', 'Année 2025'),
    ]);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: PlanningApi, useValue: api }],
    });
    TestBed.overrideComponent(ComparerVersionsView, {
      remove: { imports: [ComparaisonPanel] },
      add: { imports: [FakePanel] },
    });
  });

  async function render(address: string) {
    TestBed.inject(Location).replaceState(address);
    const fixture = TestBed.createComponent(ComparerVersionsView);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelectorAll('option').length).toBeGreaterThan(2);
    });
    return fixture;
  }

  it('opens the comparator on the pair of the address, the older side first', async () => {
    const fixture = await render('/comparer?comparer=8,7');

    const panel = fixture.debugElement.query((element) => element.name === 'app-comparaison-panel')
      .componentInstance as FakePanel;
    expect(panel.base()).toBe('7');
    expect(panel.variante()).toBe('8');
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Année 2025');
    // The reference is named on screen, not only implied by the column order.
    expect(text).toContain('Référence : Version 7');
  });

  it('asks for a pair while none is chosen, and refuses the same version twice', async () => {
    const fixture = await render('/comparer');
    const racine = fixture.nativeElement as HTMLElement;
    expect(racine.querySelector('app-comparaison-panel')).toBeNull();
    expect(racine.textContent).toContain('Choisissez deux versions pour les comparer');

    fixture.componentInstance['choose']('first', '7');
    fixture.componentInstance['choose']('second', '7');
    await fixture.whenStable();
    expect(racine.textContent).toContain('Choisissez deux versions différentes');
    expect(racine.querySelector('app-comparaison-panel')).toBeNull();
  });

  it('keeps the pair in the address, and drops it when the comparison is closed', async () => {
    const fixture = await render('/comparer');
    fixture.componentInstance['choose']('first', 'courant');
    fixture.componentInstance['choose']('second', '7');
    await fixture.whenStable();
    expect(decodeURIComponent(TestBed.inject(Location).path())).toContain('comparer=courant,7');

    fixture.componentInstance['close']();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).not.toContain('comparer=');
    // The closed panel took the focus with it: it lands back on the first list.
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('select'));
  });
});
