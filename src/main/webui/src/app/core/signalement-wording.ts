// The words of an absence reported from the espace (issue #533) that both
// sides read: the animateur in their espace, the organisation on the day's
// screen. One wording for the one closed list.

import { MotifSignalement } from './models';

/** The reason, as the animateur chose it — a closed list. */
export function motifLabel(motif: MotifSignalement): string {
  switch (motif) {
    case 'PERSONNEL':
      return $localize`:@@espace.signalement.motif.personnel:Raison personnelle`;
    case 'TRANSPORT':
      return $localize`:@@espace.signalement.motif.transport:Transport`;
    case 'AUTRE':
      return $localize`:@@espace.signalement.motif.autre:Autre raison`;
  }
}
