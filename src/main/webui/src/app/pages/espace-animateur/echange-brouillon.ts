// Draft list of the échange request form (issue #165): the animateur builds
// their demandes locally, then submits the whole list at once — the "bouton
// soumettre" only appears once the list holds something. Pure functions, so
// the rules are unit-tested without rendering.

import { NouvelleDemandeEchange, PosteAnimateurView } from '../../core/models';

/** One draft line, identified by the seat it trades away. */
export interface BrouillonDemande extends NouvelleDemandeEchange {
  /** Labels resolved at add time, so the list renders without lookups. */
  creneauLabel: string;
  standNom: string;
  cibleNom: string;
}

/** True when the form holds everything a demande needs (the motif stays optional). */
export function brouillonComplet(
  poste: PosteAnimateurView | null,
  cibleId: string
): boolean {
  return poste !== null && cibleId.trim().length > 0;
}

/**
 * Adds a draft, replacing any previous draft trading the same seat away: one
 * seat can only be given once per submission, the latest intent wins.
 */
export function ajouterBrouillon(
  brouillons: readonly BrouillonDemande[],
  nouveau: BrouillonDemande
): BrouillonDemande[] {
  return [
    ...brouillons.filter(
      (brouillon) =>
        brouillon.creneauId !== nouveau.creneauId || brouillon.standId !== nouveau.standId
    ),
    nouveau
  ];
}

export function retirerBrouillon(
  brouillons: readonly BrouillonDemande[],
  index: number
): BrouillonDemande[] {
  return brouillons.filter((ignored, position) => position !== index);
}

/** What actually leaves for the backend: the draft minus its display labels. */
export function versNouvellesDemandes(
  brouillons: readonly BrouillonDemande[]
): NouvelleDemandeEchange[] {
  return brouillons.map(({ creneauId, standId, cibleId, motif }) => ({
    creneauId,
    standId,
    cibleId,
    motif: motif && motif.trim() ? motif.trim() : null
  }));
}
