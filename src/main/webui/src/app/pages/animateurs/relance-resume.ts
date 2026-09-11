// Wording of the « Relancer maintenant » report (issue #504), for the snack
// bar: how many reminders left, then who was left alone and why — by name,
// since the organiser acts on names and the server only speaks ids.

import type { RapportRelance } from '../../core/models';

export interface ResumeRelance {
  titre: string;
  details?: string;
  variant: 'success' | 'warning';
}

/**
 * Folds the report into a title and its details. `nomDe` turns an id into
 * what the table shows, and answers the id itself for a fiche deleted in
 * between — a report must never name nobody.
 */
export function resumeRelance(
  rapport: RapportRelance,
  nomDe: (animateurId: string) => string,
): ResumeRelance {
  const noms = (ids: string[]) => ids.map(nomDe).join(', ');
  const titre = $localize`:@@animateurs.relancer.resume:${rapport.envoyes.length}:count: relance(s) envoyée(s)`;
  const details: string[] = [];
  if (rapport.dejaConfirmes.length > 0) {
    details.push(
      $localize`:@@animateurs.relancer.dejaConfirmes:Déjà confirmés : ${noms(rapport.dejaConfirmes)}:noms:`,
    );
  }
  if (rapport.dejaRelancesPourCettePublication.length > 0) {
    details.push(
      $localize`:@@animateurs.relancer.dejaRelances:Déjà relancés pour cette publication : ${noms(rapport.dejaRelancesPourCettePublication)}:noms:`,
    );
  }
  if (rapport.sansEmail.length > 0) {
    details.push(
      $localize`:@@animateurs.relancer.sansEmail:Sans adresse e-mail : ${noms(rapport.sansEmail)}:noms:`,
    );
  }
  if (rapport.sansPoste.length > 0) {
    details.push(
      $localize`:@@animateurs.relancer.sansPoste:Sans poste au planning publié : ${noms(rapport.sansPoste)}:noms:`,
    );
  }
  if (rapport.echecs.length > 0) {
    details.push(
      $localize`:@@animateurs.relancer.echecs:Échec de l'envoi : ${noms(rapport.echecs)}:noms:`,
    );
  }
  return {
    titre,
    details: details.length > 0 ? details.join(' — ') : undefined,
    variant: rapport.echecs.length > 0 ? 'warning' : 'success',
  };
}
