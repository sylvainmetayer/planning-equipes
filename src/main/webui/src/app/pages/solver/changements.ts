// The pure half of « ce qui a changé depuis cette résolution » : how the
// counts the server sends per referential family read in French. No Angular
// beyond `$localize`, so the wording is tested without rendering.

import { CompteEntite } from '../../core/models';

/**
 * What one family is called on screen, singular or plural. The server sends
 * the family's code (`ANIMATEUR`, `STAND`, …) and never a label: the history
 * screen's filter shows those codes raw, this one is read in a sentence.
 *
 * A code this build does not know reads as itself rather than disappearing —
 * a summary that silently drops a line would say « 2 animateurs » under a
 * warning caused by three changes.
 */
export function familleLisible(entite: string, nombre: number): string {
  const pluriel = nombre > 1;
  switch (entite) {
    case 'ANIMATEUR':
      return pluriel
        ? $localize`:@@changements.famille.animateurs:animateurs`
        : $localize`:@@changements.famille.animateur:animateur`;
    case 'STAND':
      return pluriel
        ? $localize`:@@changements.famille.stands:stands`
        : $localize`:@@changements.famille.stand:stand`;
    case 'CRENEAU':
      return pluriel
        ? $localize`:@@changements.famille.creneaux:créneaux`
        : $localize`:@@changements.famille.creneau:créneau`;
    case 'EMPLACEMENT':
      return pluriel
        ? $localize`:@@changements.famille.emplacements:emplacements`
        : $localize`:@@changements.famille.emplacement:emplacement`;
    case 'TYPOLOGIE':
      return pluriel
        ? $localize`:@@changements.famille.typologies:typologies`
        : $localize`:@@changements.famille.typologie:typologie`;
    case 'AJUSTEMENT':
      return pluriel
        ? $localize`:@@changements.famille.ajustements:ajustements manuels`
        : $localize`:@@changements.famille.ajustement:ajustement manuel`;
    case 'VERROUILLAGE':
      return pluriel
        ? $localize`:@@changements.famille.verrouillages:verrouillages`
        : $localize`:@@changements.famille.verrouillage:verrouillage`;
    case 'PARAMETRES':
      return pluriel
        ? $localize`:@@changements.famille.parametres:réglages`
        : $localize`:@@changements.famille.parametre:réglage`;
    case 'DISPONIBILITE':
      return pluriel
        ? $localize`:@@changements.famille.disponibilites:déclarations de disponibilités`
        : $localize`:@@changements.famille.disponibilite:déclaration de disponibilités`;
    case 'PLANNING':
      return pluriel
        ? $localize`:@@changements.famille.globaux:changements globaux`
        : $localize`:@@changements.famille.global:changement global`;
    case 'SAUVEGARDE':
      return pluriel
        ? $localize`:@@changements.famille.restaurations:restaurations de la base`
        : $localize`:@@changements.famille.restauration:restauration de la base`;
    default:
      return entite.toLowerCase();
  }
}

/** « 3 animateurs, 1 stand, 2 créneaux » — the families in the order the server sent them. */
export function resumeLisible(comptes: CompteEntite[]): string {
  return comptes
    .map((compte) => `${compte.nombre} ${familleLisible(compte.entite, compte.nombre)}`)
    .join(', ');
}
