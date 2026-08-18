import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { MutationsWhatIf, ResultatWhatIf } from '../../core/models';
import { SolverJobService } from '../../core/solver-job.service';
import { WhatIfPage } from './what-if-page';

/** Structural view of the page's template-only API. */
interface WhatIfPageApi {
  animateursAjoutes: { set(value: number): void };
  animateursRetires: { set(value: string[]): void };
  standsFermes: { set(value: string[]): void };
  standCible: { set(value: string | null): void };
  effectifCible: { set(value: number | null): void };
  mutations(): MutationsWhatIf;
  reinitialiser(): void;
  analyser(): Promise<void>;
}

function resultat(): ResultatWhatIf {
  const feasible = {
    feasible: true,
    manqueAnimateurs: 0,
    causes: [],
    totalCauses: 0,
    message: 'Réalisable'
  };
  return {
    animateurs: 10,
    animateursReference: 8,
    standsOuverts: 4,
    standsOuvertsReference: 5,
    creneaux: 12,
    reference: feasible,
    simulation: feasible
  };
}

describe('WhatIfPage', () => {
  let page: WhatIfPageApi;
  let post: ReturnType<typeof vi.fn>;
  let submitWhatIfAnalyze: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    post = vi.fn().mockResolvedValue(resultat());
    submitWhatIfAnalyze = vi.fn().mockResolvedValue({ id: 'job-1' });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get: vi.fn().mockResolvedValue([]), post } },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, onResult: () => () => undefined, submitWhatIfAnalyze }
        }
      ]
    });
    page = TestBed.createComponent(WhatIfPage).componentInstance as unknown as WhatIfPageApi;
  });

  it('sends every mutation the user set', () => {
    page.animateursAjoutes.set(3);
    page.animateursRetires.set(['A1', 'A2']);
    page.standsFermes.set(['S1']);
    page.standCible.set('S2');
    page.effectifCible.set(4);

    expect(page.mutations()).toEqual({
      animateursAjoutes: 3,
      animateursRetires: ['A1', 'A2'],
      standsFermes: ['S1'],
      effectifsMin: { S2: 4 }
    });
  });

  it('leaves the headcount alone when no stand is targeted', () => {
    page.effectifCible.set(4);
    expect(page.mutations().effectifsMin).toEqual({});
  });

  it('resets to a neutral simulation', () => {
    page.animateursAjoutes.set(5);
    page.standsFermes.set(['S1']);

    page.reinitialiser();

    expect(page.mutations()).toEqual({
      animateursAjoutes: 0,
      animateursRetires: [],
      standsFermes: [],
      effectifsMin: {}
    });
  });

  it('runs the short solve through an ANALYZE job, which never persists a plan', async () => {
    page.animateursAjoutes.set(2);
    await page.analyser();
    expect(submitWhatIfAnalyze).toHaveBeenCalledWith(
      expect.objectContaining({ animateursAjoutes: 2 }),
      expect.any(Number)
    );
  });
});
