import { describe, expect, it } from 'vitest';
import { PlanningDiagnostic } from './models';
import { extraireDiagnostic, extrairePlanPrecedent } from './solver-job.service';

// A SOLVE payload is either a bare diagnostic (full solve) or an incremental
// wrapper carrying one under `diagnostic` (issue #86). Everything downstream —
// the feasibility notification included — reads it through this one function.
describe('extraireDiagnostic', () => {
  const diagnostic = {
    score: '0hard/0medium/-1soft',
    postesNonPourvus: 0,
    contraintes: [],
    faisabilite: null,
    hardScore: 0
  } as unknown as PlanningDiagnostic;

  it('rend le diagnostic tel quel pour une résolution complète', () => {
    expect(extraireDiagnostic(diagnostic)).toBe(diagnostic);
  });

  it('déballe le diagnostic d’un résultat incrémental', () => {
    const wrapper = {
      diagnostic,
      statistiques: {
        postesTotal: 10,
        postesFiges: 9,
        postesLiberes: 1,
        postesLiberesManuellement: 0,
        postesNouveaux: 0
      },
      changements: []
    };

    expect(extraireDiagnostic(wrapper)).toBe(diagnostic);
  });

  it('déballe le diagnostic d’une résolution complète enveloppée', () => {
    expect(extraireDiagnostic({ diagnostic, previousPlan: null })).toBe(diagnostic);
  });

  it('rend null quand le job ne porte aucun résultat', () => {
    expect(extraireDiagnostic(null)).toBeNull();
    expect(extraireDiagnostic(undefined)).toBeNull();
  });
});

// Le plan que la résolution a remplacé (issue #274). Il n'existe que sur les
// enveloppes : un résultat produit avant cette version reste lisible, sans
// comparaison.
describe('extrairePlanPrecedent', () => {
  const planPrecedent = { snapshotId: 12, score: '0hard/-6232medium/-920soft', degraded: true };
  const diagnostic = {
    score: '0hard/0medium/-1soft',
    postesNonPourvus: 0,
    contraintes: [],
    faisabilite: null,
    hardScore: 0
  } as unknown as PlanningDiagnostic;

  it('rend le plan remplacé porté par le résultat', () => {
    expect(extrairePlanPrecedent({ diagnostic, previousPlan: planPrecedent })).toBe(planPrecedent);
  });

  it('rend null pour un résultat sans plan précédent', () => {
    expect(extrairePlanPrecedent({ diagnostic, previousPlan: null })).toBeNull();
    expect(extrairePlanPrecedent(diagnostic)).toBeNull();
    expect(extrairePlanPrecedent(null)).toBeNull();
  });
});
