import { describe, expect, it } from 'vitest';
import { PlanningKpi } from '../../core/models';
import { construireLignesMetriques, couverturePourcent } from './comparateur-metrics';

function kpi(overrides: Partial<PlanningKpi> = {}): PlanningKpi {
  return {
    score: '0hard/0medium/0soft',
    scoreHard: 0,
    scoreMedium: 0,
    scoreSoft: 0,
    postesTotal: 100,
    postesPourvus: 100,
    animateursAffectes: 20,
    standsDistincts: 10,
    creneauxDistincts: 10,
    heuresTotal: 200,
    heuresMoyenne: 10,
    heuresEcartType: 2,
    heuresMin: 6,
    heuresMax: 14,
    heuresIncompletes: false,
    modificationsManuelles: 0,
    tauxModificationsManuelles: 0,
    dureeSolveSecondes: 60,
    violationsParContrainte: {},
    scoreMediumHorsPlancher: null,
    plancherMedium: null,
    ...overrides,
  };
}

function ligne(base: PlanningKpi, variante: PlanningKpi, key: string) {
  const trouvee = construireLignesMetriques(base, variante).find(
    (candidate) => candidate.cle === key,
  );
  expect(trouvee, `ligne ${key}`).toBeDefined();
  return trouvee!;
}

describe('construireLignesMetriques', () => {
  it('une remontée du score medium vers zéro est une amélioration, pas une baisse', () => {
    // Les scores sont des pénalités négatives : -3 → -1 est un progrès, et
    // c'est le sens de variation que l'écran doit annoncer.
    const medium = ligne(kpi({ scoreMedium: -3 }), kpi({ scoreMedium: -1 }), 'scoreMedium');

    expect(medium.delta).toBe(2);
    expect(medium.tendance).toBe('amelioration');
  });

  it('le score medium hors plancher se compare comme le score medium, et reste inconnu sans mesure', () => {
    // A -5,000 floor on both sides: only the remainder moves, and that is what
    // is compared. A snapshot taken before the measure has no gap and no trend.
    const net = ligne(
      kpi({ scoreMedium: -6675, scoreMediumHorsPlancher: -1675 }),
      kpi({ scoreMedium: -5900, scoreMediumHorsPlancher: -900 }),
      'scoreMediumHorsPlancher',
    );
    expect(net.delta).toBe(775);
    expect(net.tendance).toBe('amelioration');

    const nonMesure = ligne(
      kpi({ scoreMediumHorsPlancher: null }),
      kpi({ scoreMediumHorsPlancher: -900 }),
      'scoreMediumHorsPlancher',
    );
    expect(nonMesure.base).toBe('—');
    expect(nonMesure.delta).toBeNull();
    expect(nonMesure.tendance).toBeNull();
  });

  it('un score qui s’éloigne de zéro est une dégradation', () => {
    expect(ligne(kpi({ scoreHard: 0 }), kpi({ scoreHard: -4 }), 'scoreHard').tendance).toBe(
      'degradation',
    );
  });

  it('un écart-type des heures qui baisse est une amélioration (fairness inversée)', () => {
    const fairness = ligne(
      kpi({ heuresEcartType: 3 }),
      kpi({ heuresEcartType: 1.5 }),
      'heuresEcartType',
    );

    expect(fairness.delta).toBe(-1.5);
    expect(fairness.tendance).toBe('amelioration');
    expect(fairness.base).toBe('3.0 h');
    expect(fairness.variante).toBe('1.5 h');
  });

  it('la volumétrie reste neutre : plus de postes n’est ni bien ni mal', () => {
    const postes = ligne(kpi({ postesTotal: 100 }), kpi({ postesTotal: 140 }), 'postesTotal');

    expect(postes.delta).toBe(40);
    expect(postes.tendance).toBeNull();
  });

  it('une métrique absente d’un côté ne vaut pas zéro : ni écart, ni tendance', () => {
    const soft = ligne(kpi({ scoreSoft: -10 }), kpi({ scoreSoft: null }), 'scoreSoft');

    expect(soft.variante).toBe('—');
    expect(soft.delta).toBeNull();
    expect(soft.tendance).toBeNull();
  });

  it('la couverture est un pourcentage, et une meilleure couverture est une amélioration', () => {
    const coverage = ligne(
      kpi({ postesTotal: 100, postesPourvus: 90 }),
      kpi({ postesTotal: 100, postesPourvus: 100 }),
      'couverture',
    );

    expect(coverage.base).toBe('90.0 %');
    expect(coverage.variante).toBe('100.0 %');
    expect(coverage.tendance).toBe('amelioration');
  });

  it('un plan sans poste n’a pas de couverture calculable plutôt qu’une couverture nulle', () => {
    expect(couverturePourcent(kpi({ postesTotal: 0, postesPourvus: 0 }))).toBeNull();
  });
});
