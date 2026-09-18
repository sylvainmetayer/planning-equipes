// The pure side of the Changements rendering: which reference and which
// reading a query param names, and the words the table puts on a change.

import { ReferenceChangementsParam } from '../../core/api/journees-api';
import {
  ChangementAnimateur,
  ChangementSiege,
  ChangementsJournee,
  ReferenceChangements,
  TypeChangementSiege,
} from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';

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

/**
 * The page's shared filters — free text, a stand, an animateur — on the seat
 * lines: a seat matches the animateur whichever side of the change they are on.
 */
export function filterSeatLines(
  lines: readonly ChangementSiege[],
  recherche: string,
  stand: string,
  animateur: string,
): ChangementSiege[] {
  return lines.filter(
    (line) =>
      (!stand || line.standId === stand) &&
      (!animateur ||
        line.avant?.animateurId === animateur ||
        line.apres?.animateurId === animateur) &&
      correspondAuFiltre(recherche, [
        line.standNom,
        line.standId,
        line.avant?.nomAffiche,
        line.apres?.nomAffiche,
      ]),
  );
}

/**
 * The people the stand filter keeps on the per-person reading: those a seat
 * line of that stand names, on either side of the change. A stand is not a
 * field of a person's line, and looking for its name in the sentences would
 * match « Tir » in « Tir à l'arc »; the seat lines carry the ids. Null when
 * no stand is picked — nothing to narrow on.
 */
export function animateurIdsOnStand(
  lines: readonly ChangementSiege[],
  stand: string,
): ReadonlySet<string> | null {
  if (!stand) {
    return null;
  }
  const ids = new Set<string>();
  for (const line of lines) {
    if (line.standId !== stand) {
      continue;
    }
    if (line.avant) {
      ids.add(line.avant.animateurId);
    }
    if (line.apres) {
      ids.add(line.apres.animateurId);
    }
  }
  return ids;
}

/**
 * The same filters on the per-person reading. `standAnimateurs` is what the
 * stand filter comes down to for a person — see {@link animateurIdsOnStand}.
 */
export function filterPersonLines(
  lines: readonly ChangementAnimateur[],
  recherche: string,
  standAnimateurs: ReadonlySet<string> | null,
  animateur: string,
): ChangementAnimateur[] {
  return lines.filter(
    (line) =>
      (!animateur || line.animateurId === animateur) &&
      (!standAnimateurs || standAnimateurs.has(line.animateurId)) &&
      correspondAuFiltre(recherche, [
        line.nomAffiche,
        ...line.changements.map((change) => change.libelle),
      ]),
  );
}

/** The four seat counters of the card, over whatever lines the filters kept. */
export interface CompteursSieges {
  nouveaux: number;
  retires: number;
  remplaces: number;
  horairesModifies: number;
}

/** Counts the seat lines by type — the server's counters, recomputed on the filtered lines. */
export function countSeatLines(lines: readonly ChangementSiege[]): CompteursSieges {
  const compteurs: CompteursSieges = { nouveaux: 0, retires: 0, remplaces: 0, horairesModifies: 0 };
  for (const line of lines) {
    switch (line.type) {
      case 'NOUVEAU':
        compteurs.nouveaux++;
        break;
      case 'RETIRE':
        compteurs.retires++;
        break;
      case 'REMPLACE':
        compteurs.remplaces++;
        break;
      case 'HORAIRES':
        compteurs.horairesModifies++;
        break;
    }
  }
  return compteurs;
}

/** « nouveau » / « retiré » / « remplacé » / « horaires modifiés » — one label per seat line. */
export function typeSiegeLabel(type: TypeChangementSiege): string {
  switch (type) {
    case 'NOUVEAU':
      return $localize`:@@journee.changements.type.nouveau:nouveau`;
    case 'RETIRE':
      return $localize`:@@journee.changements.type.retire:retiré`;
    case 'REMPLACE':
      return $localize`:@@journee.changements.type.remplace:remplacé`;
    case 'HORAIRES':
      return $localize`:@@journee.changements.type.horaires:horaires modifiés`;
  }
}
