// « Ce calcul tiendra compte de »: the wording and the links are a pure
// function; the card adds that a counter at zero stays, muted and unlinked,
// and that it re-reads when the page says a solve landed.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { SolveInputs } from '../../core/models';
import { SolveInputsCard, solveInputLines } from './solve-inputs';

function inputs(overrides: Partial<SolveInputs> = {}): SolveInputs {
  return {
    locks: 3,
    adjustments: 12,
    consignes: [
      { date: '2026-08-02', motif: 'Canicule' },
      { date: '2026-08-03', motif: 'Canicule' },
    ],
    pendingDeclarations: 2,
    disabledRules: 0,
    changesSinceSolve: 5,
    solvedAt: '2026-08-01T10:00:00Z',
    ...overrides,
  };
}

describe('solveInputLines', () => {
  it('counts each input and links it to the screen that lists it', () => {
    const lines = solveInputLines(inputs());

    expect(lines.map((line) => line.text)).toEqual([
      '3 verrouillage(s)',
      '12 ajustement(s) manuel(s)',
      '2 consigne(s) (02/08, 03/08)',
      '2 déclaration(s) de disponibilité non traitée(s), que le calcul ne verra pas',
      '0 règle(s) désactivée(s)',
      '5 modification(s) depuis la dernière résolution',
    ]);
    expect(lines[0]).toMatchObject({
      route: '/consignes-solveur',
      queryParams: { onglet: 'verrouillages' },
    });
    expect(lines[3]).toMatchObject({
      route: '/disponibilites',
      queryParams: { statut: 'en-attente' },
      warning: true,
    });
  });

  it('names no date when no consigne is laid', () => {
    expect(solveInputLines(inputs({ consignes: [] }))[2].text).toBe('0 consigne');
  });
});

describe('SolveInputsCard', () => {
  const api = { solveInputs: vi.fn() };
  let fixture: ComponentFixture<SolveInputsCard>;

  beforeEach(() => {
    api.solveInputs.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningApi, useValue: api },
      ],
    });
  });

  async function monter(value: SolveInputs): Promise<HTMLElement> {
    api.solveInputs.mockResolvedValue(value);
    fixture = TestBed.createComponent(SolveInputsCard);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect((fixture.nativeElement as HTMLElement).querySelector('li')).not.toBeNull();
    });
    return fixture.nativeElement as HTMLElement;
  }

  it('links a counter above zero, and keeps a zero muted and unlinked', async () => {
    const racine = await monter(inputs());

    const locks = racine.querySelectorAll('li')[0];
    expect(locks.querySelector('a')?.getAttribute('href')).toBe(
      '/consignes-solveur?onglet=verrouillages',
    );
    const rules = racine.querySelectorAll('li')[4];
    expect(rules.classList).toContain('solve-inputs-zero');
    expect(rules.querySelector('a')).toBeNull();
  });

  it('offers to close stands tomorrow, the nearest day a consigne can touch', async () => {
    const racine = await monter(inputs());

    const lien = Array.from(racine.querySelectorAll('a')).find((each) =>
      each.textContent!.includes('Fermer des stands demain'),
    );
    expect(lien?.getAttribute('href')).toBe(
      '/consignes-solveur?onglet=consignes&date=demain&nouvelle=1',
    );
  });

  it('reads the counts again when the page says a solve landed', async () => {
    await monter(inputs());
    expect(api.solveInputs).toHaveBeenCalledOnce();

    fixture.componentRef.setInput('version', 1);
    await fixture.whenStable();

    expect(api.solveInputs).toHaveBeenCalledTimes(2);
  });
});
