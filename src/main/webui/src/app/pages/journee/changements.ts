// The pure side of the Changements rendering: which reference and which
// reading a query param names, and the words the table puts on a change.

import { ReferenceChangementsParam } from '../../core/api/journees-api';
import { ChangementsJournee, ReferenceChangements, TypeChangementSiege } from '../../core/models';

/** The two readings of the same changes, and the values of the `lecture` query param. */
export type ChangementsReading = 'vacations' | 'animateurs';

const REFERENCES: readonly ReferenceChangementsParam[] = ['publication', 'resolution'];

/**
 * Reads the `reference` query param. Absent or unknown means « not chosen »:
 * the server then picks the publication when one exists, the last solve
 * otherwise, and the answer says which.
 */
export function readReference(value: string | null): ReferenceChangementsParam | null {
  return (REFERENCES as readonly string[]).includes(value ?? '')
    ? (value as ReferenceChangementsParam)
    : null;
}

/** Reads the `lecture` query param; anything unknown is the seat-by-seat table, the reading it opens on. */
export function readReading(value: string | null): ChangementsReading {
  return value === 'animateurs' ? 'animateurs' : 'vacations';
}

/** The query-param form of the reference an answer was computed against. */
export function referenceParam(reference: ReferenceChangements): ReferenceChangementsParam {
  return reference === 'PUBLICATION' ? 'publication' : 'resolution';
}

/** True when the day carries no change at all against an existing reference. */
export function isUnchanged(changements: ChangementsJournee): boolean {
  return (
    changements.referenceDisponible &&
    changements.parVacation.length === 0 &&
    changements.parAnimateur.length === 0
  );
}

/** « nouveau » / « retiré » / « remplacé » — one word per seat line. */
export function typeSiegeLabel(type: TypeChangementSiege): string {
  switch (type) {
    case 'NOUVEAU':
      return $localize`:@@journee.changements.type.nouveau:nouveau`;
    case 'RETIRE':
      return $localize`:@@journee.changements.type.retire:retiré`;
    case 'REMPLACE':
      return $localize`:@@journee.changements.type.remplace:remplacé`;
  }
}
