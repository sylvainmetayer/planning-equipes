// The pure half of « ce qui a changé depuis cette résolution » : how the
// counts the server sends per referential family read in French. No Angular
// beyond `$localize`, so the wording is tested without rendering.

import { CompteEntite } from '../../core/models';

/** Each family's name, singular then plural. A function, so `$localize` runs after the translations load. */
const FAMILY_LABELS: Record<string, () => [singulier: string, pluriel: string]> = {
  ANIMATEUR: () => [
    $localize`:@@changements.famille.animateur:animateur`,
    $localize`:@@changements.famille.animateurs:animateurs`,
  ],
  STAND: () => [
    $localize`:@@changements.famille.stand:stand`,
    $localize`:@@changements.famille.stands:stands`,
  ],
  CRENEAU: () => [
    $localize`:@@changements.famille.creneau:créneau`,
    $localize`:@@changements.famille.creneaux:créneaux`,
  ],
  EMPLACEMENT: () => [
    $localize`:@@changements.famille.emplacement:emplacement`,
    $localize`:@@changements.famille.emplacements:emplacements`,
  ],
  TYPOLOGIE: () => [
    $localize`:@@changements.famille.typologie:typologie`,
    $localize`:@@changements.famille.typologies:typologies`,
  ],
  AJUSTEMENT: () => [
    $localize`:@@changements.famille.ajustement:ajustement manuel`,
    $localize`:@@changements.famille.ajustements:ajustements manuels`,
  ],
  VERROUILLAGE: () => [
    $localize`:@@changements.famille.verrouillage:verrouillage`,
    $localize`:@@changements.famille.verrouillages:verrouillages`,
  ],
  PARAMETRES: () => [
    $localize`:@@changements.famille.parametre:réglage`,
    $localize`:@@changements.famille.parametres:réglages`,
  ],
  DISPONIBILITE: () => [
    $localize`:@@changements.famille.disponibilite:déclaration de disponibilités`,
    $localize`:@@changements.famille.disponibilites:déclarations de disponibilités`,
  ],
  PLANNING: () => [
    $localize`:@@changements.famille.global:changement global`,
    $localize`:@@changements.famille.globaux:changements globaux`,
  ],
  SAUVEGARDE: () => [
    $localize`:@@changements.famille.restauration:restauration de la base`,
    $localize`:@@changements.famille.restaurations:restaurations de la base`,
  ],
};

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
  const libelles = Object.hasOwn(FAMILY_LABELS, entite) ? FAMILY_LABELS[entite] : undefined;
  if (!libelles) {
    return entite.toLowerCase();
  }
  const [singulier, pluriel] = libelles();
  return nombre > 1 ? pluriel : singulier;
}

/** « 3 animateurs, 1 stand, 2 créneaux » — the families in the order the server sent them. */
export function resumeLisible(comptes: CompteEntite[]): string {
  return comptes
    .map((compte) => `${compte.nombre} ${familleLisible(compte.entite, compte.nombre)}`)
    .join(', ');
}
