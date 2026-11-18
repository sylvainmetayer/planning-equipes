// The on-demand compute machine (ADR 0086): its state in words, and the two
// gestures offered only in the states where the server accepts them.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MachineCalculApi } from '../../core/api/machine-calcul-api';
import { MachineCalcul } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { MachineCalculCard } from './machine-calcul-card';

function machine(overrides: Partial<MachineCalcul> = {}): MachineCalcul {
  return {
    configured: true,
    onDemand: true,
    remoteByDefault: false,
    state: 'ABSENT',
    stopsAt: null,
    billedHoursThisMonth: null,
    lastStopError: null,
    ...overrides,
  };
}

describe('MachineCalculCard', () => {
  const api = fakeOf<MachineCalculApi>({ prepare: vi.fn(), stop: vi.fn() });
  let fixture: ComponentFixture<MachineCalculCard>;

  beforeEach(() => {
    api.prepare.mockReset();
    api.stop.mockReset();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideFake(MachineCalculApi, api)],
    });
  });

  function render(view: MachineCalcul): HTMLElement {
    fixture = TestBed.createComponent(MachineCalculCard);
    fixture.componentRef.setInput('machine', view);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function buttons(root: HTMLElement): string[] {
    return [...root.querySelectorAll('button')].map((button) => button.textContent!.trim());
  }

  it('offers to prepare an absent machine, and emits the view the server answers', async () => {
    const root = render(machine());
    const changed: MachineCalcul[] = [];
    fixture.componentInstance.changed.subscribe((view) => changed.push(view));
    api.prepare.mockResolvedValue(machine({ state: 'PREPARING' }));

    expect(root.textContent).toContain('Absente');
    expect(buttons(root)).toEqual([expect.stringContaining('Préparer la machine')]);
    root.querySelector('button')!.click();
    await fixture.whenStable();

    expect(api.prepare).toHaveBeenCalledOnce();
    expect(changed).toEqual([machine({ state: 'PREPARING' })]);
  });

  it('offers to stop a ready machine, with its stop time and its hours', () => {
    const root = render(
      machine({ state: 'READY', stopsAt: '2026-11-18T14:05:00Z', billedHoursThisMonth: 7 }),
    );

    expect(root.textContent).toContain('Prête');
    expect(root.textContent).toContain('Arrêt prévu à');
    expect(root.textContent).toContain('7 h de machine ce mois-ci');
    expect(buttons(root)).toEqual([expect.stringContaining('Arrêter la machine maintenant')]);
  });

  it('offers nothing while the machine solves, and shows a failed stop as an error', () => {
    const root = render(machine({ state: 'SOLVING', lastStopError: 'launcher: 503' }));

    expect(buttons(root)).toEqual([]);
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('launcher: 503');
  });

  it('reports a refused stop', async () => {
    const root = render(machine({ state: 'READY' }));
    const failed: string[] = [];
    fixture.componentInstance.failed.subscribe((message) => failed.push(message));
    api.stop.mockRejectedValue(new Error('Un calcul tourne sur la machine.'));

    root.querySelector('button')!.click();
    await fixture.whenStable();

    expect(failed).toHaveLength(1);
    expect(failed[0]).toContain('Un calcul tourne sur la machine.');
  });
});
