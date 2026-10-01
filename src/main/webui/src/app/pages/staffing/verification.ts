// How a staffing check reads, on the staffing screen and in the history. No
// Angular here, so it is unit-tested without rendering anything.

import { StaffingVerification } from '../../core/models';

type Team = Pick<StaffingVerification, 'effectif' | 'majeurs' | 'mineurs'>;

/** « 150 personnes », or « 150 personnes (140 majeurs, 10 mineurs) » once minors are in it. */
export function teamLabel(verification: Team): string {
  const size = verification.effectif;
  if (verification.mineurs <= 0) {
    return $localize`:@@staffing.verification.equipe:${size}:effectif: personnes`;
  }
  const adults = verification.majeurs;
  const minors = verification.mineurs;
  return $localize`:@@staffing.verification.equipeMixte:${size}:effectif: personnes (${adults}:majeurs: majeurs, ${minors}:mineurs: mineurs)`;
}

/**
 * One line of the history: the team, the time it was given, and how it ended
 * — « 160 personnes, 600 s au plus : tous les sièges pourvus en 312 s ».
 */
export function historyLabel(verification: StaffingVerification): string {
  const team = teamLabel(verification);
  const limit = verification.plafondSecondes;
  const trial = $localize`:@@historique.verification.essai:${team}:equipe:, ${limit}:plafond: s au plus`;
  if (verification.etat === 'EN_COURS') {
    return $localize`:@@historique.verification.enCours:${trial}:essai: : en cours`;
  }
  if (verification.etat === 'ECHEC') {
    const error = verification.erreur ?? '';
    return $localize`:@@historique.verification.echec:${trial}:essai: : ${error}:erreur:`;
  }
  const seconds = verification.dureeSecondes ?? 0;
  if (verification.realisable) {
    return $localize`:@@historique.verification.ok:${trial}:essai: : tous les sièges pourvus en ${seconds}:duree: s`;
  }
  const empty = verification.siegesNonPourvus ?? 0;
  return $localize`:@@historique.verification.ko:${trial}:essai: : aucun plan complet, ${empty}:vides: sièges vides`;
}
