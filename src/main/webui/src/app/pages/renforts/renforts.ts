import { LigneRenfort, RapportRenforts } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';

/**
 * What the « Renforts » screen computes over the report, kept out of the
 * component so it is unit-tested without rendering: the sort, the quick
 * filter, the per-day lookup the grid reads, and the two shares the summary
 * states.
 */

/** How the rows are ordered. The default is what the server already sorted on. */
export type TriRenforts = 'ouvertes' | 'pourvues' | 'inutilisees' | 'nom';

const TRIS: readonly TriRenforts[] = ['ouvertes', 'pourvues', 'inutilisees', 'nom'];

/** Tolerant read of `?tri=`: an unknown value is the default, never a failed page. */
export function readTri(value: string | null): TriRenforts {
  return TRIS.includes(value as TriRenforts) ? (value as TriRenforts) : 'ouvertes';
}

/** Bonus hours the stand opened and nobody took — what a cut would cost nothing. */
export function heuresInutilisees(ligne: LigneRenfort): number {
  return arrondi(Math.max(0, ligne.heuresOuvertes - ligne.heuresPourvues));
}

/**
 * Hours of one stand on one day, `null` when that day holds neither an opened
 * nor a staffed renfort — a hole the grid draws as such rather than as a zero.
 */
export function celluleDuJour(
  ligne: LigneRenfort,
  date: string,
): { ouvertes: number; pourvues: number } | null {
  const cellule = ligne.jours.find((jour) => jour.date === date);
  return cellule ? { ouvertes: cellule.heuresOuvertes, pourvues: cellule.heuresPourvues } : null;
}

/** The rows the screen shows: filtered on the stand's name or location, then sorted. */
export function lignesAffichees(
  rapport: RapportRenforts | undefined,
  filtre: string,
  tri: TriRenforts,
): LigneRenfort[] {
  const lignes = (rapport?.stands ?? []).filter((ligne) =>
    correspondAuFiltre(filtre, [ligne.nom, ligne.standId, ligne.emplacementNom]),
  );
  const triees = [...lignes];
  switch (tri) {
    case 'nom':
      triees.sort((a, b) => a.nom.localeCompare(b.nom, 'fr'));
      break;
    case 'pourvues':
      triees.sort(
        (a, b) => b.heuresPourvues - a.heuresPourvues || a.nom.localeCompare(b.nom, 'fr'),
      );
      break;
    case 'inutilisees':
      triees.sort(
        (a, b) => heuresInutilisees(b) - heuresInutilisees(a) || a.nom.localeCompare(b.nom, 'fr'),
      );
      break;
    default:
      triees.sort(
        (a, b) => b.heuresOuvertes - a.heuresOuvertes || a.nom.localeCompare(b.nom, 'fr'),
      );
  }
  return triees;
}

/**
 * Share of the declared bonus the plan actually took, between 0 and 1, or
 * `null` when nothing is opened — a ratio over zero says nothing.
 */
export function tauxEmploi(rapport: RapportRenforts | undefined): number | null {
  if (!rapport || rapport.heuresOuvertes <= 0) {
    return null;
  }
  return rapport.heuresPourvues / rapport.heuresOuvertes;
}

/**
 * Share the bonus adds on top of what the event owes, or `null` when nothing
 * is owed. It is the figure a budget conversation starts from: « les renforts
 * pèsent 12 % de plus que le nécessaire ».
 */
export function partDuBonus(rapport: RapportRenforts | undefined): number | null {
  if (!rapport || rapport.heuresDues <= 0) {
    return null;
  }
  return rapport.heuresOuvertes / rapport.heuresDues;
}

function arrondi(heures: number): number {
  return Math.round(heures * 100) / 100;
}
