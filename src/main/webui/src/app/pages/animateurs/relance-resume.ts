// Wording of the « Relancer maintenant » report (issue #504), for the snack
// bar: how many reminders left, then who was left alone and why — by name,
// since the organiser acts on names and the server only speaks ids.
//
// The names stay in the snack bar. What the notification journal keeps is the
// same report counted rather than named: that journal lives in `localStorage`
// (see `docs/rgpd.md` §7), it is not cleared on sign-out and it is read back on
// the Notifications page, so a list that can cover the whole roster has no
// business being written to the machine of the régie.

import type { RapportRelance } from '../../core/models';

export interface ResumeRelance {
  titre: string;
  details?: string;
  /** The same details, by the numbers: what the persisted journal keeps. */
  detailsJournal?: string;
  variant: 'success' | 'warning';
}

/** Beyond this many names the snack bar is unreadable anyway, and says how many more. */
const NOMS_MAX = 8;

/**
 * Folds the report into a title and its details. `nomDe` turns an id into
 * what the table shows, and answers the id itself for a fiche deleted in
 * between — a report must never name nobody.
 */
export function resumeRelance(
  rapport: RapportRelance,
  nomDe: (animateurId: string) => string,
): ResumeRelance {
  const noms = (ids: string[]) => {
    const affiches = ids.slice(0, NOMS_MAX).map(nomDe).join(', ');
    const restants = ids.length - NOMS_MAX;
    return restants > 0
      ? affiches + $localize`:@@animateurs.relancer.autres: et ${restants}:count: autre(s)`
      : affiches;
  };
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
  if (rapport.adresseRefusee.length > 0) {
    details.push(
      $localize`:@@animateurs.relancer.adresseRefusee:Adresse refusée au dernier envoi, fiche à corriger : ${noms(rapport.adresseRefusee)}:noms:`,
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
    detailsJournal: details.length > 0 ? comptes(rapport) : undefined,
    variant: rapport.echecs.length > 0 || rapport.adresseRefusee.length > 0 ? 'warning' : 'success',
  };
}

/** The same report without a single name: counts only, for the persisted journal. */
function comptes(rapport: RapportRelance): string {
  return $localize`:@@animateurs.relancer.journal:${rapport.dejaConfirmes.length}:confirmes: déjà confirmé(s), ${rapport.dejaRelancesPourCettePublication.length}:relances: déjà relancé(s), ${rapport.sansEmail.length}:sansEmail: sans adresse, ${rapport.sansPoste.length}:sansPoste: sans poste, ${rapport.echecs.length}:echecs: en échec, ${rapport.adresseRefusee.length}:adresseRefusee: adresse(s) refusée(s)`;
}
