// Metric rows of the A/B comparator (issue #70): pure functions, so the
// direction-of-improvement semantics can be unit-tested without a DOM.

import { PlanningKpi } from '../../core/models';

export type Tendance = 'amelioration' | 'degradation' | null;

export interface LigneMetrique {
  cle: string;
  label: string;
  base: string;
  variante: string;
  /** variante − base, null when either side is unknown. */
  delta: number | null;
  /**
   * Whether the delta is an improvement. Null for neutral metrics (volumetry,
   * durations) and unknown values. Scores are stored as negative penalties, so
   * a score getting closer to zero is an improvement — "une baisse de score
   * medium est une amélioration" reads, on the raw value, as −3 → −1, which is
   * a positive delta here.
   */
  tendance: Tendance;
}

/** 'plus' = a higher value is better, 'moins' = lower is better, null = neutral. */
type Sens = 'plus' | 'moins' | null;

export function construireLignesMetriques(base: PlanningKpi, variante: PlanningKpi): LigneMetrique[] {
  const lignes: LigneMetrique[] = [];
  const ajouter = (
    cle: string,
    label: string,
    valeurBase: number | null,
    valeurVariante: number | null,
    sens: Sens,
    formatter: (valeur: number) => string = (valeur) => String(valeur)
  ): void => {
    const delta = valeurBase === null || valeurVariante === null ? null : valeurVariante - valeurBase;
    lignes.push({
      cle,
      label,
      base: valeurBase === null ? '—' : formatter(valeurBase),
      variante: valeurVariante === null ? '—' : formatter(valeurVariante),
      delta,
      tendance: tendance(delta, sens)
    });
  };

  const heures = (valeur: number): string => `${valeur.toFixed(1)} h`;
  ajouter('scoreHard', $localize`:@@comparateur.metric.scoreHard:Score hard`, base.scoreHard, variante.scoreHard, 'plus');
  ajouter(
    'scoreMedium',
    $localize`:@@comparateur.metric.scoreMedium:Score medium`,
    base.scoreMedium,
    variante.scoreMedium,
    'plus'
  );
  ajouter('scoreSoft', $localize`:@@comparateur.metric.scoreSoft:Score soft`, base.scoreSoft, variante.scoreSoft, 'plus');
  ajouter(
    'couverture',
    $localize`:@@comparateur.metric.couverture:Couverture (postes pourvus)`,
    couverturePourcent(base),
    couverturePourcent(variante),
    'plus',
    (valeur) => `${valeur.toFixed(1)} %`
  );
  ajouter(
    'heuresEcartType',
    $localize`:@@comparateur.metric.heuresEcartType:Fairness — écart-type des heures`,
    base.heuresEcartType,
    variante.heuresEcartType,
    'moins',
    heures
  );
  ajouter(
    'heuresMoyenne',
    $localize`:@@comparateur.metric.heuresMoyenne:Heures moyennes par animateur`,
    base.heuresMoyenne,
    variante.heuresMoyenne,
    null,
    heures
  );
  ajouter(
    'heuresMin',
    $localize`:@@comparateur.metric.heuresMin:Heures du moins chargé`,
    base.heuresMin,
    variante.heuresMin,
    null,
    heures
  );
  ajouter(
    'heuresMax',
    $localize`:@@comparateur.metric.heuresMax:Heures du plus chargé`,
    base.heuresMax,
    variante.heuresMax,
    'moins',
    heures
  );
  ajouter('postesTotal', $localize`:@@comparateur.metric.postesTotal:Postes`, base.postesTotal, variante.postesTotal, null);
  ajouter(
    'animateursAffectes',
    $localize`:@@comparateur.metric.animateursAffectes:Animateurs affectés`,
    base.animateursAffectes,
    variante.animateursAffectes,
    null
  );
  ajouter('standsDistincts', $localize`:@@comparateur.metric.stands:Stands`, base.standsDistincts, variante.standsDistincts, null);
  ajouter(
    'creneauxDistincts',
    $localize`:@@comparateur.metric.creneaux:Créneaux`,
    base.creneauxDistincts,
    variante.creneauxDistincts,
    null
  );
  ajouter(
    'modificationsManuelles',
    $localize`:@@comparateur.metric.modifications:Modifications manuelles (contraintes ad hoc + verrouillages)`,
    base.modificationsManuelles,
    variante.modificationsManuelles,
    null
  );
  ajouter(
    'dureeSolve',
    $localize`:@@comparateur.metric.dureeSolve:Durée de résolution`,
    base.dureeSolveSecondes,
    variante.dureeSolveSecondes,
    null,
    (valeur) => `${valeur} s`
  );
  return lignes;
}

export function couverturePourcent(kpi: PlanningKpi): number | null {
  return kpi.postesTotal === 0 ? null : (kpi.postesPourvus / kpi.postesTotal) * 100;
}

function tendance(delta: number | null, sens: Sens): Tendance {
  if (delta === null || delta === 0 || sens === null) {
    return null;
  }
  const meilleur = sens === 'plus' ? delta > 0 : delta < 0;
  return meilleur ? 'amelioration' : 'degradation';
}
