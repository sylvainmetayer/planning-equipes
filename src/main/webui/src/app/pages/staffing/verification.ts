// How a staffing check reads, on the staffing screen and in the history. No
// Angular here, so it is unit-tested without rendering anything.

import { StaffingVerification } from '../../core/models';

type Team = Pick<StaffingVerification, 'effectif' | 'majeurs' | 'mineurs'>;

/** « 150 personnes », or « 150 personnes (140 majeurs, 10 mineurs) » once minors are in it. */
export function teamLabel(verification: Team): string {
  const effectif = verification.effectif;
  if (verification.mineurs <= 0) {
    return $localize`:@@staffing.verification.equipe:${effectif}:effectif: personnes`;
  }
  const majeurs = verification.majeurs;
  const mineurs = verification.mineurs;
  return $localize`:@@staffing.verification.equipeMixte:${effectif}:effectif: personnes (${majeurs}:majeurs: majeurs, ${mineurs}:mineurs: mineurs)`;
}

/**
 * One line of the history: the team, the time it was given, and how it ended
 * — « 160 personnes, 600 s au plus : tous les sièges pourvus en 312 s ».
 */
export function historyLabel(verification: StaffingVerification): string {
  const equipe = teamLabel(verification);
  const plafond = verification.plafondSecondes;
  const essai = $localize`:@@historique.verification.essai:${equipe}:equipe:, ${plafond}:plafond: s au plus`;
  if (verification.etat === 'EN_COURS') {
    return $localize`:@@historique.verification.enCours:${essai}:essai: : en cours`;
  }
  if (verification.etat === 'ECHEC') {
    const erreur = verification.erreur ?? '';
    return $localize`:@@historique.verification.echec:${essai}:essai: : ${erreur}:erreur:`;
  }
  const duree = verification.dureeSecondes ?? 0;
  if (verification.realisable) {
    return $localize`:@@historique.verification.ok:${essai}:essai: : tous les sièges pourvus en ${duree}:duree: s`;
  }
  const vides = verification.siegesNonPourvus ?? 0;
  return $localize`:@@historique.verification.ko:${essai}:essai: : aucun plan complet, ${vides}:vides: sièges vides`;
}
