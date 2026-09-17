// Builders of the « planning par typologie » screen (issue #590): pure
// functions over the rows the server counts, kept out of the component so the
// filters, the bars and the heat scale are tested without rendering.

import { LigneTypologie } from '../../core/models';

/** Which typologies the cap filter keeps. */
export type CapFilter = 'all' | 'capped' | 'uncapped';

/**
 * What the toolbar narrows the reading with. All four apply together: the
 * question is « parmi les typologies plafonnées, lesquelles personne ne
 * tient », not one criterion at a time.
 */
export interface TypologieFilters {
  /** Free text over the id, the label and the description. */
  search: string;
  cap: CapFilter;
  /** Keeps the typologies whose cap is at least this; `null` when unset. */
  capMinimum: number | null;
  /** Keeps only what {@link underTension} reports. */
  tensionOnly: boolean;
}

export const EMPTY_FILTERS: TypologieFilters = {
  search: '',
  cap: 'all',
  capMinimum: null,
  tensionOnly: false,
};

/**
 * A typologie worth a second look: nobody was sat at it, somebody vetted on it
 * was never used, or somebody was used without being vetted. None of the three
 * is a fault on its own — a stand proposing several typologies makes the last
 * one ordinary — but together they are the short list an organiser opens this
 * screen to get.
 */
export function underTension(ligne: LigneTypologie): boolean {
  return (
    ligne.postes === 0 ||
    ligne.competentsJamaisAffectes.length > 0 ||
    ligne.affectesSansCompetence.length > 0
  );
}

/** The rows the four filters keep, in the order the server sent them. */
export function filterTypologies(
  lignes: readonly LigneTypologie[],
  filters: TypologieFilters,
): LigneTypologie[] {
  const search = filters.search.trim().toLocaleLowerCase();
  return lignes.filter((ligne) => {
    if (
      search &&
      ![ligne.typologie, ligne.label, ligne.description ?? '']
        .join(' ')
        .toLocaleLowerCase()
        .includes(search)
    ) {
      return false;
    }
    const cap = ligne.maxCreneauxParAnimateur;
    if (filters.cap === 'capped' && cap == null) {
      return false;
    }
    if (filters.cap === 'uncapped' && cap != null) {
      return false;
    }
    // A minimum only ever speaks of capped typologies: an uncapped one has no
    // number to compare, and « au moins 3 » never means « pas de plafond ».
    if (filters.capMinimum !== null && (cap == null || cap < filters.capMinimum)) {
      return false;
    }
    return !filters.tensionOnly || underTension(ligne);
  });
}

/** One bar of the comparison tab: a row, and its share of the largest one. */
export interface BarreTypologie {
  ligne: LigneTypologie;
  /** Share of the busiest typologie's hours, in percent — the bar's width. */
  partHeures: number;
  /** Same for the seats, so the two bars of a row are read against each other. */
  partPostes: number;
}

/**
 * The bars, busiest first. Widths are relative to the busiest row rather than
 * to an absolute: a 40-hour edition and a 4000-hour one get the same picture,
 * which is the one the comparison is about.
 */
export function barres(lignes: readonly LigneTypologie[]): BarreTypologie[] {
  const maxHeures = Math.max(0, ...lignes.map((ligne) => ligne.heures));
  const maxPostes = Math.max(0, ...lignes.map((ligne) => ligne.postes));
  return [...lignes]
    .sort((a, b) => b.heures - a.heures || a.label.localeCompare(b.label))
    .map((ligne) => ({
      ligne,
      partHeures: maxHeures === 0 ? 0 : Math.round((ligne.heures / maxHeures) * 100),
      partPostes: maxPostes === 0 ? 0 : Math.round((ligne.postes / maxPostes) * 100),
    }));
}

/**
 * The heat class of a typologie × jour cell, on the four steps the heatmap
 * already defines, read against the busiest cell of the table.
 */
export function classeHeures(heures: number, maximum: number): string {
  if (heures <= 0 || maximum <= 0) {
    return 'heatmap-cell heatmap-cell-none';
  }
  const part = heures / maximum;
  if (part > 0.66) {
    return 'heatmap-cell heatmap-cell-critical';
  }
  return part > 0.33 ? 'heatmap-cell heatmap-cell-warning' : 'heatmap-cell heatmap-cell-ok';
}

/** The busiest cell of the typologie × jour table — the scale's reference. */
export function dailyMaximum(lignes: readonly LigneTypologie[], jours: readonly string[]): number {
  let maximum = 0;
  for (const ligne of lignes) {
    for (const jour of jours) {
      maximum = Math.max(maximum, ligne.heuresParJour?.[jour] ?? 0);
    }
  }
  return maximum;
}
