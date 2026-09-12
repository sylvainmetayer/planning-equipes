// Display labels of the demande d'échange lifecycle (issue #165), shared by
// the espace animateur and the admin screen. Called lazily (never at module
// scope, see `shell/admin-shell.ts`'s `buildNavGroups`).

import { DemandeEchangeView, StatutDemandeEchange } from './models';

export function statutDemandeLabel(statut: StatutDemandeEchange): string {
  switch (statut) {
    case 'EN_ATTENTE_CIBLE':
      return $localize`:@@echanges.statut.attenteCible:En attente du collègue`;
    case 'PROPOSEE':
      return $localize`:@@echanges.statut.proposee:En attente de l'organisation`;
    case 'ACCEPTEE':
      return $localize`:@@echanges.statut.acceptee:Acceptée`;
    case 'REFUSEE':
      return $localize`:@@echanges.statut.refusee:Refusée`;
    case 'REFUSEE_CIBLE':
      return $localize`:@@echanges.statut.refuseeCible:Déclinée par le collègue`;
    case 'ANNULEE':
      return $localize`:@@echanges.statut.annulee:Annulée`;
  }
}

/** CSS modifier of the statut chip: `espace-statut-<modifier>` / `echanges-statut-<modifier>`. */
export function statutDemandeClasse(statut: StatutDemandeEchange): string {
  switch (statut) {
    case 'EN_ATTENTE_CIBLE':
    case 'PROPOSEE':
      return 'attente';
    case 'ACCEPTEE':
      return 'acceptee';
    case 'REFUSEE':
    case 'REFUSEE_CIBLE':
      return 'refusee';
    case 'ANNULEE':
      return 'annulee';
  }
}

/**
 * An organisation decision the publication has not carried yet (issue #531).
 * Until it leaves, the espace keeps serving the plan published before it: an
 * acceptation has moved the working plan only, so « Acceptée » sits above a
 * planning that still shows the previous seat.
 *
 * The two screens read it differently, on purpose: the espace only says it of
 * an acceptation — the one case where the statut contradicts what the person
 * sees — while the admin says it of both decisions, because that is their list
 * of what is left to publish.
 *
 * Accepted and refused, and no other statut: a demande the animateur withdrew
 * is stamped `decideLe` too (it shares the column), but nobody decided
 * anything about it and there is nothing to announce back to its author.
 */
export function decisionNonCommuniquee(demande: DemandeEchangeView): boolean {
  return (
    (demande.statut === 'ACCEPTEE' || demande.statut === 'REFUSEE') &&
    demande.decideLe !== null &&
    demande.communiqueeLe === null
  );
}
