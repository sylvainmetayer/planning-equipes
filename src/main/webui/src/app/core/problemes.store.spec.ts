import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { ProblemesStore } from './problemes.store';
import type { CauseInfaisabilite, ConstraintsView, FeasibilityReport } from './models';

function cause(overrides: Partial<CauseInfaisabilite> = {}): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite: 'ELEVE',
    message: 'Il manque 2 animateurs.',
    creneauId: '12',
    date: '2026-08-01',
    heureDebut: '12:30',
    heureFin: '15:30',
    standIds: ['tir'],
    contrainteIds: [],
    demande: 6,
    capacite: 4,
    manque: 2,
    ...overrides,
  };
}

function report(causes: CauseInfaisabilite[]): FeasibilityReport {
  return {
    feasible: causes.length === 0,
    manqueAnimateurs: 2,
    causes,
    totalCauses: causes.length,
    message: 'Planning non réalisable en l’état.',
  };
}

function constraintsView(): ConstraintsView {
  return {
    analysedAt: '2026-07-01T10:00:00Z',
    scoreGlobal: '-2hard/0medium/0soft',
    postesNonPourvus: 0,
    faisabilite: null,
    hardScore: -2,
    contraintesAdHocEnCause: [],
    contraintes: [
      {
        name: 'dureeHebdomadaireMax',
        niveau: 'HARD',
        categorie: 'Légal',
        description: 'Durée hebdomadaire maximale.',
        actif: true,
        protegee: true,
        dosable: false,
        poids: 1,
        score: '-2hard/0medium/0soft',
        matchCount: 2,
        violations: ['Alice : 52 h'],
      },
    ],
  };
}

/** Fake ApiService routing GETs by URL, failing on anything not scripted. */
class FakeApi {
  responses: Record<string, unknown> = {};
  get = vi.fn(async (url: string) => {
    if (!(url in this.responses)) {
      throw new Error(`Unexpected GET ${url}`);
    }
    const response = this.responses[url];
    if (response instanceof Error) {
      throw response;
    }
    return response;
  });
}

describe('ProblemesStore', () => {
  let store: ProblemesStore;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [ProblemesStore, { provide: ApiService, useValue: api }],
    });
    store = TestBed.inject(ProblemesStore);
  });

  describe('reloadFeasibility', () => {
    it('starts empty and reports nothing infeasible', () => {
      expect(store.report()).toBeNull();
      expect(store.infeasible()).toBe(false);
      expect(store.problemes()).toEqual([]);
    });

    it('stores the report and clears any previous error', async () => {
      api.responses = { '/api/feasibility': report([cause()]) };
      await store.reloadFeasibility();
      expect(store.infeasible()).toBe(true);
      expect(store.causes()).toHaveLength(1);
      expect(store.error()).toBe('');
      expect(store.loading()).toBe(false);
    });

    // The endpoint may be missing on an older server: badging pages must survive it.
    it('keeps a null report and records the error when the endpoint fails', async () => {
      api.responses = { '/api/feasibility': new Error('HTTP 404') };
      await store.reloadFeasibility();
      expect(store.report()).toBeNull();
      expect(store.infeasible()).toBe(false);
      expect(store.error()).toBe('HTTP 404');
      expect(store.loading()).toBe(false);
    });
  });

  describe('reload', () => {
    it('merges both sources into one ranked list', async () => {
      api.responses = {
        '/api/feasibility': report([cause({ severite: 'CRITIQUE' })]),
        '/api/constraints': constraintsView(),
      };
      await store.reload();
      expect(store.problemes().map((probleme) => probleme.source)).toEqual([
        'FAISABILITE',
        'CONTRAINTE',
      ]);
      expect(store.comptage()).toEqual({ bloquants: 2, avertissements: 0, mineurs: 0, total: 2 });
    });

    it('folds the relay-less breaks into the list and into the one-line alert', async () => {
      api.responses = {
        '/api/feasibility': report([]),
        '/api/constraints': { ...constraintsView(), contraintes: [] },
        '/api/pauses': {
          pauseSurPoste: true,
          journeesAnalysees: 1,
          pausesDues: 1,
          relaisManquants: 1,
          message: '',
          journees: [
            {
              animateurId: 'alice',
              nomComplet: 'Alice Martin',
              mineur: false,
              date: '2026-08-01',
              jour: 1,
              sequences: [
                {
                  debut: '13:00:00',
                  fin: '20:00:00',
                  minutes: 420,
                  pausesDues: [
                    {
                      debut: '19:00:00',
                      fin: '19:20:00',
                      heureLimite: '19:00:00',
                      dureeMinutes: 20,
                      standId: 'tir',
                      standNom: 'Tir',
                      relais: [],
                      relaisDisponible: false,
                      simultanee: false,
                    },
                  ],
                },
              ],
              pausesPlanifiees: [],
            },
          ],
        },
      };
      await store.reload();
      expect(store.pauses()?.relaisManquants).toBe(1);
      expect(store.problemes().map((probleme) => probleme.source)).toEqual(['PAUSES']);
      expect(store.problemes()[0].liens[0].route).toBe('/pauses');
      expect(store.alertePausesSansRelais()).toContain('1 pause(s) légale(s) sans relais');
      expect(store.error()).toBe('');
    });

    it('says nothing about breaks when every one has a relay, or when the endpoint fails', async () => {
      api.responses = {
        '/api/feasibility': report([]),
        '/api/constraints': { ...constraintsView(), contraintes: [] },
        '/api/pauses': {
          ...{
            pauseSurPoste: true,
            journeesAnalysees: 1,
            pausesDues: 1,
            relaisManquants: 1,
            message: '',
            journees: [
              {
                animateurId: 'alice',
                nomComplet: 'Alice Martin',
                mineur: false,
                date: '2026-08-01',
                jour: 1,
                sequences: [
                  {
                    debut: '13:00:00',
                    fin: '20:00:00',
                    minutes: 420,
                    pausesDues: [
                      {
                        debut: '19:00:00',
                        fin: '19:20:00',
                        heureLimite: '19:00:00',
                        dureeMinutes: 20,
                        standId: 'tir',
                        standNom: 'Tir',
                        relais: [],
                        relaisDisponible: false,
                        simultanee: false,
                      },
                    ],
                  },
                ],
                pausesPlanifiees: [],
              },
            ],
          },
          relaisManquants: 0,
        },
      };
      await store.reload();
      expect(store.alertePausesSansRelais()).toBe('');
      expect(store.problemes()).toEqual([]);

      // A missing endpoint shortens the list; it is never the screen's failure.
      api.responses = {
        '/api/feasibility': report([]),
        '/api/constraints': { ...constraintsView(), contraintes: [] },
      };
      await store.reload();
      expect(store.pauses()).toBeNull();
      expect(store.alertePausesSansRelais()).toBe('');
      expect(store.error()).toBe('');

      // An answer that is not a report is ignored the same way.
      api.responses['/api/pauses'] = 'pas un rapport';
      await store.reload();
      expect(store.pauses()).toBeNull();
    });

    it('still exposes the source that answered when the other one fails', async () => {
      api.responses = {
        '/api/feasibility': new Error('HTTP 404'),
        '/api/constraints': constraintsView(),
      };
      await store.reload();
      expect(store.report()).toBeNull();
      expect(store.constraints()).not.toBeNull();
      expect(store.problemes()).toHaveLength(1);
      expect(store.error()).toBe('HTTP 404');
    });
  });

  describe('badge indexes', () => {
    it('are empty while nothing is loaded', () => {
      expect(store.causeParStandId().size).toBe(0);
      expect(store.causeParCreneauId().size).toBe(0);
      expect(store.causeCritiqueParDate().size).toBe(0);
      expect(store.causeParContrainteAdHocId().size).toBe(0);
    });

    it('indexes every ad hoc constraint named by a contradiction', async () => {
      api.responses = {
        '/api/feasibility': report([
          cause({
            type: 'CONTRAINTES_AD_HOC_CONTRADICTOIRES',
            severite: 'CRITIQUE',
            message: 'C1 et C2 se contredisent.',
            standIds: [],
            contrainteIds: ['C1', 'C2'],
          }),
        ]),
      };
      await store.reloadFeasibility();
      // Both are badged: which one to delete is the user's arbitration.
      expect([...store.causeParContrainteAdHocId().keys()]).toEqual(['C1', 'C2']);
      expect(store.causeParContrainteAdHocId().get('C2')?.message).toBe(
        'C1 et C2 se contredisent.',
      );
    });

    it('indexes every stand named by a cause', async () => {
      api.responses = { '/api/feasibility': report([cause({ standIds: ['tir', 'quilles'] })]) };
      await store.reloadFeasibility();
      expect([...store.causeParStandId().keys()]).toEqual(['tir', 'quilles']);
      expect(store.causeParStandId().get('quilles')?.message).toBe('Il manque 2 animateurs.');
      expect(store.causeParStandId().has('echecs')).toBe(false);
    });

    it('keeps the first (most severe) cause when a stand appears twice', async () => {
      api.responses = {
        '/api/feasibility': report([
          cause({ severite: 'CRITIQUE', message: 'la plus grave' }),
          cause({ severite: 'ELEVE', message: 'la moins grave' }),
        ]),
      };
      await store.reloadFeasibility();
      expect(store.causeParStandId().get('tir')?.message).toBe('la plus grave');
    });

    it('keys créneaux by their stringified id and skips causes without one', async () => {
      api.responses = {
        '/api/feasibility': report([
          cause({ creneauId: '12' }),
          cause({ creneauId: null, date: null }),
        ]),
      };
      await store.reloadFeasibility();
      expect([...store.causeParCreneauId().keys()]).toEqual(['12']);
      expect(store.causeParCreneauId().get(String(12))).toBeDefined();
    });

    it('indexes dates of CRITIQUE causes only', async () => {
      api.responses = {
        '/api/feasibility': report([
          cause({ severite: 'ELEVE', date: '2026-08-01' }),
          cause({ severite: 'CRITIQUE', date: '2026-08-02' }),
        ]),
      };
      await store.reloadFeasibility();
      expect([...store.causeCritiqueParDate().keys()]).toEqual(['2026-08-02']);
    });
  });
});
