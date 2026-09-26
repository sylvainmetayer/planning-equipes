// The figures the Stands table shows besides the fiche — the days a stand is
// open and the seats it asks for, what the persisted plan filled of them — and
// the order of its columns. Pure, shared by the table and by the fiche stand,
// whose « précédent / suivant » walks the table in the order it was sorted.

import { PlanningEvenement, RapportOuvertures, Stand } from '../../core/models';
import { SortValue, sortRows } from '../../core/table-sort';
import { SortState } from '../../core/view-query-params';

/** How much of the event a stand is open: its days with any opening, and the seats they ask for. */
export interface StandOpening {
  joursOuverts: number;
  postes: number;
}

/** What the persisted plan filled of a stand's seats. */
export interface StandCoverage {
  pourvus: number;
  postes: number;
}

/** What the columns sort on besides the stand itself. */
export interface StandSortContext {
  typologies: ReadonlyMap<string, string>;
  openings: ReadonlyMap<string, StandOpening>;
  coverage: ReadonlyMap<string, StandCoverage>;
}

/** Each stand's open days and seats, read from the openings report. */
export function standOpenings(rapport: RapportOuvertures | null): Map<string, StandOpening> {
  return new Map(
    (rapport?.stands ?? []).map((ligne) => [
      ligne.standId,
      {
        joursOuverts: ligne.jours.filter((jour) => jour.etat !== 'FERME').length,
        postes: ligne.postes,
      },
    ]),
  );
}

/**
 * Each stand's seats in the persisted plan and how many are held; empty when
 * nobody holds any seat yet — before a solve there is no coverage to show.
 */
export function standCoverage(planning: PlanningEvenement | null): Map<string, StandCoverage> {
  const postes = planning?.postes ?? [];
  if (!postes.some((poste) => poste.animateur)) {
    return new Map();
  }
  const coverage = new Map<string, StandCoverage>();
  for (const poste of postes) {
    const standId = poste.stand?.id;
    if (!standId) {
      continue;
    }
    const entry = coverage.get(standId) ?? { pourvus: 0, postes: 0 };
    entry.postes++;
    if (poste.animateur) {
      entry.pourvus++;
    }
    coverage.set(standId, entry);
  }
  return coverage;
}

/** The share of a stand's seats held, `null` for a stand without seats. */
export function coverageRate(coverage: StandCoverage | undefined): number | null {
  return coverage && coverage.postes > 0 ? coverage.pourvus / coverage.postes : null;
}

/** A stand's game categories by label, an unknown id kept as-is rather than dropped. */
export function typologieLabelsOf(stand: Stand, typologies: ReadonlyMap<string, string>): string[] {
  return (stand.typologiesProposees ?? []).map((id) => typologies.get(id) || id);
}

/**
 * What one column sorts on, the value its cell shows: a text, a count, a
 * share. A missing figure — a stand the openings report does not know, one
 * without seats — answers `null`, which sorts last whichever the direction.
 * An unknown column answers `undefined` for every stand: an old link degrades
 * to the natural order of the ids.
 */
export function standSortValue(stand: Stand, column: string, context: StandSortContext): SortValue {
  switch (column) {
    case 'id':
      return stand.id;
    case 'code':
      return stand.code;
    case 'nom':
      return stand.nom;
    case 'effectif':
      return stand.effectifMin * 1000 + stand.effectifMax;
    case 'typologies':
      return typologieLabelsOf(stand, context.typologies).join(', ');
    case 'emplacement':
      return stand.emplacement?.nom;
    case 'ouvert': {
      const opening = context.openings.get(stand.id);
      return opening ? opening.joursOuverts * 1_000_000 + opening.postes : null;
    }
    case 'couverture':
      return coverageRate(context.coverage.get(stand.id));
    default:
      return undefined;
  }
}

/**
 * The stands in the sort's order, ties and the unsorted table in the natural
 * order of the ids (T2 before T10) — the table's order, which the fiche's
 * « précédent / suivant » walks.
 */
export function sortStands(
  stands: readonly Stand[],
  sort: SortState,
  context: StandSortContext,
): Stand[] {
  return sortRows(
    stands,
    sort,
    (stand, column) => standSortValue(stand, column, context),
    (stand) => stand.id,
  );
}
