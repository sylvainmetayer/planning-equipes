// Formatting and matching shared by the Réalisé vs planifié page, the
// Versions page and the Besoin tab: no class name here, so the pages that
// read a measure do not inherit the grid's stylesheet.

import { intlLocale } from '../../core/locale';
import { GapCounts, GapTotal, PreviousEdition } from '../../core/models';

/** Hours, from the minutes the server counts in: « 12,5 h ». */
export function heures(minutes: number): string {
  const format = new Intl.NumberFormat(intlLocale(), { maximumFractionDigits: 1 });
  return `${format.format(minutes / 60)} h`;
}

/** A rate as a percentage, « — » when there is nothing to divide by. */
export function pourcentage(taux: number | null): string {
  if (taux === null) {
    return '—';
  }
  return new Intl.NumberFormat(intlLocale(), { style: 'percent', maximumFractionDigits: 0 }).format(
    taux,
  );
}

/**
 * The previous edition's measure for one game category of this edition, by
 * name — case and accents aside: a game category id is a per-edition
 * counter, and last year's `T1` may be another category altogether. The id
 * only breaks a tie between two categories of the same name. Null when the
 * previous edition had no category of that name.
 */
export function mesurePrecedente(
  precedente: PreviousEdition | null | undefined,
  typologieId: string,
  label: string,
): GapTotal | null {
  if (!precedente?.available) {
    return null;
  }
  const nom = normalise(label);
  const homonymes = precedente.byTypologie.filter((total) => normalise(total.label) === nom);
  return homonymes.find((total) => total.key === typologieId) ?? homonymes[0] ?? null;
}

function normalise(texte: string): string {
  return texte
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .trim()
    .toLowerCase();
}

/** « 12 % d'absence · 3 h perdues »: what the Besoin tab shows, for information only. */
export function resumePrecedent(counts: GapCounts): string {
  return $localize`:@@realise.precedente.typologie:${pourcentage(counts.absenceRate)}:taux: d'absence · ${heures(counts.lostMinutes)}:perdues: perdues`;
}
