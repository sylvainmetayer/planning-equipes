// Display labels of the demande d'échange lifecycle (issue #165), shared by
// the espace animateur and the admin screen. Called lazily (never at module
// scope, see `shell/admin-shell.ts`'s `buildNavGroups`).

import { StatutDemandeEchange } from './models';

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
