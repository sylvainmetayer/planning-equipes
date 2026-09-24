// Presentation rules of the "Ouvertures des stands" screen, kept apart from the
// component so they are unit-tested without rendering — same split as the bulk
// edits (`<entity>-bulk-edit.ts`).
//
// Nothing here recomputes a schedule: the server already resolved it from the
// postes a solve would receive. This file only decides how to *show* it.

import {
  AnomalieOuverture,
  CelluleJourOuverture,
  LigneStandOuverture,
  RapportOuvertures,
  TypeAnomalieOuverture,
} from '../../core/models';

/** Which rows to show: everything, or only what deserves a second look. */
export type FiltreOuvertures = 'TOUS' | 'ANOMALIES' | 'PARTIELS' | 'FERMES';

/**
 * Bar width of a cell, as a percentage of the day's amplitude. A stand open a
 * few minutes out of ten hours must still show a visible sliver rather than
 * nothing — that is exactly the case worth spotting.
 */
export function largeurPourcent(cellule: CelluleJourOuverture): number {
  if (cellule.minutesAmplitude <= 0 || cellule.minutesOuvertes <= 0) {
    return 0;
  }
  const brut = (cellule.minutesOuvertes / cellule.minutesAmplitude) * 100;
  return Math.max(4, Math.min(100, Math.round(brut)));
}

/** `135` → `2 h 15`, `45` → `45 min`. Labels come from the caller, so no `$localize` here. */
export function dureeCourte(
  minutes: number,
  libelles: { heures: string; minutes: string },
): string {
  if (minutes <= 0) {
    return '—';
  }
  if (minutes < 60) {
    return `${minutes} ${libelles.minutes}`;
  }
  const heures = Math.floor(minutes / 60);
  const reste = minutes % 60;
  return reste === 0 ? `${heures} ${libelles.heures}` : `${heures} ${libelles.heures} ${reste}`;
}

/** Ids of the stands carrying at least one anomaly, for the filter and the row badge. */
export function standsEnAnomalie(anomalies: readonly AnomalieOuverture[]): Set<string> {
  return new Set(anomalies.map((anomaly) => anomaly.standId));
}

/** The anomalies of one stand, so a row can carry its own tooltip. */
export function anomaliesParStand(
  anomalies: readonly AnomalieOuverture[],
): Map<string, AnomalieOuverture[]> {
  const parStand = new Map<string, AnomalieOuverture[]>();
  for (const anomaly of anomalies) {
    const liste = parStand.get(anomaly.standId) ?? [];
    liste.push(anomaly);
    parStand.set(anomaly.standId, liste);
  }
  return parStand;
}

/**
 * Rows the filter keeps. `ANOMALIES` is the validation shortcut — on sixty-odd
 * stands, scrolling the whole grid to find the three that are wrong defeats the
 * purpose of the screen.
 */
export function filtrerStands(
  rapport: RapportOuvertures,
  filtre: FiltreOuvertures,
  recherche: string,
): LigneStandOuverture[] {
  const enAnomalie = standsEnAnomalie(rapport.anomalies);
  const terme = recherche.trim().toLocaleLowerCase();
  return rapport.stands.filter((ligne) => {
    if (terme && !`${ligne.standId} ${ligne.nom}`.toLocaleLowerCase().includes(terme)) {
      return false;
    }
    switch (filtre) {
      case 'TOUS':
        return true;
      case 'ANOMALIES':
        return enAnomalie.has(ligne.standId);
      case 'PARTIELS':
        return ligne.jours.some((jour) => jour.etat === 'OUVERT_PARTIEL');
      case 'FERMES':
        return ligne.jours.some((jour) => jour.etat === 'FERME');
    }
  });
}

/** Counts for the summary line: how many stands fall in each state at least once. */
export interface SyntheseOuvertures {
  stands: number;
  jamaisOuverts: number;
  avecJourFerme: number;
  avecJourPartiel: number;
  postesTotal: number;
  anomalies: number;
}

export function synthese(rapport: RapportOuvertures): SyntheseOuvertures {
  return {
    stands: rapport.stands.length,
    jamaisOuverts: rapport.standsJamaisOuverts,
    avecJourFerme: rapport.stands.filter((ligne) =>
      ligne.jours.some((jour) => jour.etat === 'FERME'),
    ).length,
    avecJourPartiel: rapport.stands.filter((ligne) =>
      ligne.jours.some((jour) => jour.etat === 'OUVERT_PARTIEL'),
    ).length,
    postesTotal: rapport.postesTotal,
    anomalies: rapport.anomalies.length,
  };
}

/** CSS class of a cell, driving its colour: state first, then which layer decided it. */
export function classeCellule(cellule: CelluleJourOuverture): string {
  const etat =
    cellule.etat === 'FERME' ? 'ferme' : cellule.etat === 'OUVERT_PARTIEL' ? 'partiel' : 'total';
  const source =
    cellule.source === 'EXCEPTION'
      ? ' source-exception'
      : cellule.source === 'REGLE'
        ? ' source-regle'
        : '';
  return `ouverture-cellule etat-${etat}${source}`;
}

/** Icon of an anomaly kind, so the list reads without colour alone. */
export function iconeAnomalie(type: TypeAnomalieOuverture): string {
  switch (type) {
    case 'STAND_JAMAIS_OUVERT':
      return 'block';
    case 'FENETRE_SANS_EFFET':
      return 'help_outline';
    case 'SEGMENT_TROP_COURT':
      return 'hourglass_bottom';
    case 'REGLES_CHEVAUCHANTES':
      return 'layers';
    case 'REGLE_MASQUEE':
      return 'visibility_off';
    case 'FENETRES_CHEVAUCHANTES':
      return 'join_inner';
  }
}
