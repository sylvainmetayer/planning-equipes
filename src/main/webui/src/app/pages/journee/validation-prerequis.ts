// The wording of the four prerequisites, kept out of the component so it is
// unit-tested without rendering — and so the sentence, not the figure, is the
// only thing that changes between French and English.

import { PrerequisValidation } from '../../core/models';

/** What one prerequisite says on screen, counts included. */
export function libellePrerequis(prerequis: PrerequisValidation): string {
  if (!prerequis.connu) {
    return notChecked(prerequis);
  }
  switch (prerequis.code) {
    case 'ECARTS_DURS':
      return prerequis.satisfait
        ? $localize`:@@journee.validation.ecarts.ok:Aucun écart dur ce jour-là.`
        : $localize`:@@journee.validation.ecarts.ko:${prerequis.nombre}:count: écart(s) dur(s) ce jour-là.`;
    case 'SIEGES_VIDES':
      return prerequis.satisfait
        ? $localize`:@@journee.validation.sieges.ok:Aucun siège vide.`
        : $localize`:@@journee.validation.sieges.ko:${prerequis.nombre}:count: siège(s) sans animateur.`;
    case 'PAUSES_NON_RELAYEES':
      return prerequis.satisfait
        ? $localize`:@@journee.validation.pauses.ok:Toutes les pauses sont relayées.`
        : $localize`:@@journee.validation.pauses.ko:${prerequis.nombre}:count: pause(s) sans personne pour prendre le relais.`;
    default:
      return prerequis.satisfait
        ? $localize`:@@journee.validation.irremplacables.ok:Aucun poste irremplaçable.`
        : $localize`:@@journee.validation.irremplacables.ko:${prerequis.nombre}:count: poste(s) reposant sur quelqu'un d'irremplaçable.`;
  }
}

/**
 * What an unknown prerequisite says. The rule analysis lives in the server's
 * memory and a restart empties it: « non vérifié » is the honest answer, where
 * « satisfait » would acknowledge a measurement nobody made.
 */
function notChecked(prerequis: PrerequisValidation): string {
  switch (prerequis.code) {
    case 'ECARTS_DURS':
      return $localize`:@@journee.validation.ecarts.inconnu:Écarts durs : non vérifié, aucune analyse en mémoire (relancez une analyse depuis Contraintes).`;
    case 'PAUSES_NON_RELAYEES':
      return $localize`:@@journee.validation.pauses.inconnu:Pauses relayées : non vérifié.`;
    case 'POSTES_IRREMPLACABLES':
      return $localize`:@@journee.validation.irremplacables.inconnu:Postes irremplaçables : non vérifié.`;
    default:
      return $localize`:@@journee.validation.sieges.inconnu:Sièges vides : non vérifié.`;
  }
}
