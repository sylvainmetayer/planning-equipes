// Presentation rules of the « Horaires des stands » screen, kept apart from the
// component so they are unit-tested without rendering — same split as the bulk
// edits (`<entity>-bulk-edit.ts`).
//
// Nothing here recomputes a schedule: the server already resolved it from the
// postes a solve would receive. This file only decides how to *show* it.

import {
  AnomalieOuverture,
  LigneStandOuverture,
  RapportOuvertures,
  TypeAnomalieOuverture,
} from '../../core/models';

/**
 * The grid (typed and read in one place), the same grid said once per kind of
 * day (ADR 0033), and two to eight stands laid side by side. The two grids
 * write the same cells: the one by kind of day says a timeslot once for every
 * date its template governs.
 */
export type OpeningsView = 'GRILLE' | 'JOURNEES_TYPES' | 'COMPARER';

/** The `vue` query param of each view; the grid, the default, writes none. */
export const OPENINGS_VIEW_PARAMS: Readonly<Record<OpeningsView, string | null>> = {
  GRILLE: null,
  JOURNEES_TYPES: 'journees-types',
  COMPARER: 'comparer',
};

/**
 * `saisie` was the entry grid's own name before the reading grid folded into
 * it, and the « Que faire ? » actions still write it: it reads as the grid.
 * `journee` and `calendrier` are redirected before the page is built
 * (`vues-retirees.ts`); here they only fall back to the default.
 */
export function readOpeningsView(param: string | null): OpeningsView {
  if (param === 'journees-types') {
    return 'JOURNEES_TYPES';
  }
  return param === 'comparer' ? 'COMPARER' : 'GRILLE';
}

/** Which rows to show: everything, or only what deserves a second look. */
export type FiltreOuvertures = 'TOUS' | 'ANOMALIES' | 'PARTIELS' | 'FERMES';

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
