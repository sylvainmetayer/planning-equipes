import { describe, expect, it } from 'vitest';
import { PlanningDiagnostic } from './models';
import { extraireDiagnostic } from './solver-job.service';

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

  it('rend null quand le job ne porte aucun résultat', () => {
    expect(extraireDiagnostic(null)).toBeNull();
    expect(extraireDiagnostic(undefined)).toBeNull();
  });
});
