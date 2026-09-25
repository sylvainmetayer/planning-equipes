// What a game category is worth to the planning, read on the referential the
// page already holds — no plan, no solve. The « État de l'édition » counts the
// same thing server-side (`CoherenceAnalyzer.typologieUsages`); both replay
// `usage-typologies.cas.json`, so the badge here and the count there are one
// definition.

import { Animateur, Stand, TypologieItem } from '../../core/models';

/**
 * - `ORPHELINE`: a stand proposes it and nobody holds it — only a polyvalent
 *   can take the stand;
 * - `FRAGILE`: a stand proposes it and exactly one person holds it;
 * - `INUTILISEE`: no stand proposes it — information, never an alert;
 * - `SANS_COMPETENT_INUTILISEE`: neither a stand nor a holder — a candidate
 *   for deletion;
 * - `NORMALE`: nothing to say.
 */
export type EtatTypologie =
  'ORPHELINE' | 'FRAGILE' | 'INUTILISEE' | 'SANS_COMPETENT_INUTILISEE' | 'NORMALE';

export interface UsageTypologie {
  typologieId: string;
  /**
   * Animateurs holding an appreciation on it, whatever the level. A polyvalent
   * holding only the ninja category is not one (ADR 0017: a reinforcement,
   * never a specialist).
   */
  competents: number;
  referents: number;
  autonomes: number;
  debutants: number;
  /** The ninjas who could still take its stands without holding it. */
  polyvalents: number;
  souhaits: number;
  /** Stands proposing it. */
  stands: number;
  etat: EtatTypologie;
}

/** The states worth acting upon, in the order the filter offers them. */
export const ETATS_A_TRAITER: readonly EtatTypologie[] = ['ORPHELINE', 'FRAGILE'];

const ETATS: readonly EtatTypologie[] = [
  'ORPHELINE',
  'FRAGILE',
  'INUTILISEE',
  'SANS_COMPETENT_INUTILISEE',
  'NORMALE',
];

/**
 * The usage of every typologie, in their order.
 *
 * Three choices, each held by a shared case. The ninja category is not
 * proposed by stands but held by people, so it is judged on its holders alone.
 * An edition without any animateur raises no orphan: the Référentiels step
 * already says the roster is empty. A stand proposing several categories
 * counts for each of them.
 */
export function usagesTypologies(
  typologies: readonly TypologieItem[],
  stands: readonly Stand[],
  animateurs: readonly Animateur[],
): UsageTypologie[] {
  const ninjaId = typologies.find((typologie) => typologie.ninja)?.id ?? null;
  return typologies.map((typologie) => {
    const id = typologie.id;
    let referents = 0;
    let autonomes = 0;
    let debutants = 0;
    let polyvalents = 0;
    let souhaits = 0;
    for (const animateur of animateurs) {
      const competences = animateur.competences ?? {};
      if (Object.hasOwn(competences, id)) {
        const niveau = competences[id];
        if (niveau === 'REFERENT') {
          referents++;
        } else if (niveau === 'AUTONOME') {
          autonomes++;
        } else {
          debutants++;
        }
      } else if (ninjaId !== null && Object.hasOwn(competences, ninjaId)) {
        polyvalents++;
      }
      if ((animateur.souhaits ?? []).includes(id)) {
        souhaits++;
      }
    }
    const competents = referents + autonomes + debutants;
    const proposants = stands.filter((stand) =>
      (stand.typologiesProposees ?? []).includes(id),
    ).length;
    return {
      typologieId: id,
      competents,
      referents,
      autonomes,
      debutants,
      polyvalents,
      souhaits,
      stands: proposants,
      etat: etat(id === ninjaId, competents, proposants, animateurs.length > 0),
    };
  });
}

function etat(
  ninja: boolean,
  competents: number,
  stands: number,
  rosterEntered: boolean,
): EtatTypologie {
  if (ninja) {
    return competents > 0 ? 'NORMALE' : 'INUTILISEE';
  }
  if (stands === 0) {
    return competents === 0 && rosterEntered ? 'SANS_COMPETENT_INUTILISEE' : 'INUTILISEE';
  }
  if (!rosterEntered) {
    return 'NORMALE';
  }
  if (competents === 0) {
    return 'ORPHELINE';
  }
  return competents === 1 ? 'FRAGILE' : 'NORMALE';
}

/** The `etat` query param value of a state: lower case, `-` for `_`. */
export function etatParam(etat: EtatTypologie): string {
  return etat.toLowerCase().replaceAll('_', '-');
}

/**
 * Reads `?etat=orpheline,fragile`, tolerant: an unknown value is dropped, and
 * nothing readable means no filter.
 */
export function readEtatsParam(value: string | null): EtatTypologie[] {
  if (!value) {
    return [];
  }
  const demandes = new Set(value.split(',').map((morceau) => morceau.trim().toLowerCase()));
  return ETATS.filter((etat) => demandes.has(etatParam(etat)));
}

/** The inverse of {@link readEtatsParam}; `null` when there is no filter. */
export function etatsQueryParam(etats: readonly EtatTypologie[]): string | null {
  return etats.length === 0 ? null : etats.map(etatParam).join(',');
}

/** The badge text; empty for a typologie with nothing to say. */
export function libelleEtat(etat: EtatTypologie): string {
  switch (etat) {
    case 'ORPHELINE':
      return $localize`:@@typologies.etat.orpheline:Orpheline`;
    case 'FRAGILE':
      return $localize`:@@typologies.etat.fragile:Fragile`;
    case 'INUTILISEE':
      return $localize`:@@typologies.etat.inutilisee:Inutilisée`;
    case 'SANS_COMPETENT_INUTILISEE':
      return $localize`:@@typologies.etat.sansCompetentInutilisee:Sans compétent, inutilisée`;
    case 'NORMALE':
      return '';
  }
}

export function iconeEtat(etat: EtatTypologie): string {
  switch (etat) {
    case 'ORPHELINE':
      return 'error';
    case 'FRAGILE':
      return 'warning';
    default:
      return 'info';
  }
}

/** What the badge means, in one sentence — its tooltip. */
export function explicationEtat(usage: UsageTypologie): string {
  switch (usage.etat) {
    case 'ORPHELINE':
      return usage.polyvalents > 0
        ? $localize`:@@typologies.etat.orpheline.hintPolyvalents:Proposée par ${usage.stands}:stands: stand(s), maîtrisée par personne : tenable seulement par les ${usage.polyvalents}:polyvalents: polyvalent(s).`
        : $localize`:@@typologies.etat.orpheline.hint:Proposée par ${usage.stands}:stands: stand(s), maîtrisée par personne.`;
    case 'FRAGILE':
      return $localize`:@@typologies.etat.fragile.hint:Une seule personne maîtrise cette typologie.`;
    case 'INUTILISEE':
      return $localize`:@@typologies.etat.inutilisee.hint:Aucun stand ne la propose.`;
    case 'SANS_COMPETENT_INUTILISEE':
      return $localize`:@@typologies.etat.sansCompetentInutilisee.hint:Ni stand ni compétent : candidate à la suppression.`;
    case 'NORMALE':
      return '';
  }
}

/** « 2 référent(s) · 3 autonome(s) · 1 débutant(s) », and the polyvalents beside. */
export function repartitionCompetents(usage: UsageTypologie): string {
  const repartition = $localize`:@@typologies.competents.repartition:${usage.referents}:referents: référent(s) · ${usage.autonomes}:autonomes: autonome(s) · ${usage.debutants}:debutants: débutant(s)`;
  return usage.polyvalents > 0
    ? repartition +
        $localize`:@@typologies.competents.polyvalents:, sans compter ${usage.polyvalents}:polyvalents: polyvalent(s)`
    : repartition;
}
