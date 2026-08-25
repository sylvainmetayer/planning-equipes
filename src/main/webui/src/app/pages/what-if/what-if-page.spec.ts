// The mutation-building half below is a logic test. The rendering half at the
// end is here because this page's answer is a *sentence*, not a number: "cette
// variante rend le planning réalisable" is the whole product, and which of the
// three verdicts is on screen — improves, degrades, or neither — is decided in
// the template alone.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
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

describe('WhatIfPage rendering', () => {
  let fixture: ComponentFixture<WhatIfPage>;
  let post: ReturnType<typeof vi.fn>;
  let submitWhatIfAnalyze: ReturnType<typeof vi.fn>;

  function rapport(feasible: boolean, manque = 0) {
    return {
      feasible,
      manqueAnimateurs: manque,
      causes: [],
      totalCauses: 0,
      message: feasible ? 'Réalisable' : 'Non réalisable'
    };
  }

  async function rendre(reponse: ResultatWhatIf | Error): Promise<void> {
    post = vi.fn(async () => {
      if (reponse instanceof Error) {
        throw reponse;
      }
      return reponse;
    });
    submitWhatIfAnalyze = vi.fn(async () => ({ id: 'job-1' }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get: vi.fn(async () => []), post } },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, onResult: () => () => undefined, submitWhatIfAnalyze }
        }
      ]
    });
    fixture = TestBed.createComponent(WhatIfPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function texte(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle)
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  it('spells out the verdict and the two deltas of the variant', async () => {
    await rendre(resultat());

    expect(texte()).toContain('Variante réalisable : 10 animateurs pour 4 stands ouverts.');
    const deltas = Array.from(racine().querySelectorAll('.what-if-delta')).map((each) => each.textContent!.trim());
    // Ten against a reference of eight, four stands against five.
    expect(deltas).toEqual(['(+2)', '(-1)']);
  });

  it('celebrates a variant that makes an impossible planning feasible', async () => {
    await rendre({ ...resultat(), reference: rapport(false, 3), simulation: rapport(true) });

    expect(texte()).toContain('rend le planning réalisable');
    expect(texte()).not.toContain('rend le planning infaisable');
  });

  it('warns about a variant that breaks a feasible planning', async () => {
    await rendre({ ...resultat(), reference: rapport(true), simulation: rapport(false, 2) });

    expect(texte()).toContain('rend le planning infaisable');
    expect(texte()).toContain('Variante non réalisable : 2 animateur(s) manquant(s)');
  });

  it('stays neutral when the variant changes nothing about feasibility', async () => {
    await rendre(resultat());

    expect(texte()).not.toContain('rend le planning réalisable');
    expect(texte()).not.toContain('rend le planning infaisable');
  });

  it('shows the error and no stale result when the simulation fails', async () => {
    await rendre(new Error('serveur indisponible'));

    expect(texte()).toContain('serveur indisponible');
    expect(racine().querySelector('.what-if-deltas')).toBeNull();
  });

  it('re-simulates on reset, without the mutations the user had set', async () => {
    await rendre(resultat());
    const avant = post.mock.calls.length;

    bouton('Réinitialiser').click();
    await fixture.whenStable();

    expect(post.mock.calls.length).toBe(avant + 1);
    expect(post.mock.calls.at(-1)![1]).toEqual({
      animateursAjoutes: 0,
      animateursRetires: [],
      standsFermes: [],
      effectifsMin: {}
    });
  });

  it('runs the short solve on the variant, with the duration on screen', async () => {
    await rendre(resultat());

    const duree = racine().querySelector('.solver-duration input') as HTMLInputElement;
    duree.value = '45';
    duree.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    bouton('Analyser la variante').click();
    await fixture.whenStable();

    expect(submitWhatIfAnalyze).toHaveBeenCalledWith(
      { animateursAjoutes: 0, animateursRetires: [], standsFermes: [], effectifsMin: {} },
      45
    );
    // The button must not invite a second job while the first one runs.
    expect(bouton('Analyser la variante').disabled).toBe(true);
  });

  it('hides the score card entirely while no simulation has answered', async () => {
    await rendre(new Error('hors ligne'));

    expect(racine().textContent!).not.toContain('Analyser la variante');
  });
});
