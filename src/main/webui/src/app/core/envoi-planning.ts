// Shared rendering of a planning-send report (`/api/planning/envoi/*`),
// used by the Solveur page (send to everyone) and the animateur timeline
// (individual send).

import type { CompteRenduEnvoi } from './models';

/**
 * Folds a send report into a one-line title plus optional details naming who
 * was skipped (no address) or failed — the admin acts on names, not counts.
 */
export function resumeEnvoi(compteRendu: CompteRenduEnvoi): { titre: string; details?: string } {
  const titre = $localize`:@@envoi.resume.succes:${compteRendu.envoyes}:count: planning(s) envoyé(s)`;
  const details: string[] = [];
  if (compteRendu.sansEmail.length > 0) {
    details.push(
      $localize`:@@envoi.resume.sansEmail:Sans adresse e-mail : ${compteRendu.sansEmail.join(', ')}:noms:`
    );
  }
  if (compteRendu.echecs.length > 0) {
    details.push($localize`:@@envoi.resume.echecs:Échec de l'envoi : ${compteRendu.echecs.join(', ')}:noms:`);
  }
  return { titre, details: details.length > 0 ? details.join(' — ') : undefined };
}
