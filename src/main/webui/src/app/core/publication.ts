// Wording of the publication block (`/api/planning/publication`, issue #245).
//
// The count is a piece of pending work, not a warning: it names how many
// people are waiting to be told, goes to zero once they have been, and stays
// there. Kept apart from the component so the sentences can be pinned down
// without a DOM.

import type { ApercuPublication, RapportPublication } from './models';

/**
 * The button's own label, count included: « Publier — 3 personnes concernées ».
 * With nobody concerned it says so instead of inviting a click that would be
 * refused server-side.
 */
export function libellePublier(apercu: ApercuPublication | null): string {
  if (!apercu || apercu.nombreConcernes === 0) {
    return $localize`:@@publication.bouton.rien:Tout le monde est à jour`;
  }
  return apercu.nombreConcernes === 1
    ? $localize`:@@publication.bouton.une:Publier — 1 personne concernée`
    : $localize`:@@publication.bouton.plusieurs:Publier — ${apercu.nombreConcernes}:count: personnes concernées`;
}

/**
 * Why the button is unavailable, in one sentence — or an empty string when it
 * is available. A disabled control that does not say why is a dead end.
 */
export function raisonIndisponible(apercu: ApercuPublication | null): string {
  if (!apercu) {
    return '';
  }
  if (apercu.solveEnCours) {
    return $localize`:@@publication.bloque.solve:Une résolution est en cours sur cette édition : publier maintenant figerait un plan sur le point d'être réécrit.`;
  }
  if (apercu.planVide) {
    return $localize`:@@publication.bloque.vide:Aucun planning enregistré à publier.`;
  }
  return '';
}

/**
 * « Jamais publié » or the date of the last publication, as a plain fact.
 * Takes anything carrying the date — the publication preview of the Solveur
 * page, the confirmation synthesis of the Animateurs page — so both screens
 * word it the same way.
 */
export function libelleDernierePublication(
  apercu: Pick<ApercuPublication, 'dernierePublicationLe'> | null,
  locale: string,
): string {
  if (!apercu?.dernierePublicationLe) {
    return $localize`:@@publication.derniere.jamais:Jamais publié`;
  }
  const quand = new Date(apercu.dernierePublicationLe).toLocaleString(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  return $localize`:@@publication.derniere.le:Dernière publication le ${quand}:quand:`;
}

/**
 * Folds a publication report into a one-line title plus optional details
 * naming who was skipped (no address, refused address) or failed — the admin
 * acts on names, not counts.
 */
export function resumePublication(rapport: RapportPublication): {
  titre: string;
  details?: string;
} {
  const titre = $localize`:@@publication.resume.succes:Planning publié — ${rapport.envoyes}:count: personne(s) prévenue(s)`;
  const details: string[] = [];
  if (rapport.sansEmail.length > 0) {
    details.push(
      $localize`:@@publication.resume.sansEmail:Sans adresse e-mail : ${rapport.sansEmail.join(', ')}:noms:`,
    );
  }
  if (rapport.echecs.length > 0) {
    details.push(
      $localize`:@@publication.resume.echecs:Échec de l'envoi : ${rapport.echecs.join(', ')}:noms:`,
    );
  }
  // Not attempted: the relay refused these addresses on the last send, and
  // nothing leaves for them until the address is corrected.
  if (rapport.adresseRefusee.length > 0) {
    details.push(
      $localize`:@@publication.resume.adresseRefusee:Adresse refusée au dernier envoi, non prévenu(s) — corrigez l'adresse ou appelez : ${rapport.adresseRefusee.join(', ')}:noms:`,
    );
  }
  // Said in the same breath as the sends, and not as a warning: deferring
  // somebody is a decision the admin just took, and what they need back is the
  // confirmation that nothing was lost by taking it.
  if (rapport.differes.length > 0) {
    details.push(
      $localize`:@@publication.resume.differes:Non prévenu(s), à reprendre à la prochaine publication : ${rapport.differes.join(', ')}:noms:`,
    );
  }
  return { titre, details: details.length > 0 ? details.join(' — ') : undefined };
}
