// The last mail sent to one animateur, in words (issue #663): what the
// Animateurs column and the fiche say of it. A send is not a reading — « parti »
// means handed to the mail server, nothing more — and under a mocked mailer
// (`MAIL_MOCK=true`) nothing left at all, which the wording says rather than
// letting a staging server pass for a delivery.

import type { CategorieEchecEnvoi, LastMailDelivery } from '../../core/models';

/** Why a mail could not leave, as the organiser reads it. */
export function failureCategoryLabel(categorie: CategorieEchecEnvoi | null | undefined): string {
  switch (categorie) {
    case 'RELAIS_INJOIGNABLE':
      return $localize`:@@animateurs.envoi.categorie.relaisInjoignable:serveur de messagerie injoignable`;
    case 'AUTHENTIFICATION':
      return $localize`:@@animateurs.envoi.categorie.authentification:identifiants du serveur de messagerie refusés`;
    case 'ADRESSE_REFUSEE':
      return $localize`:@@animateurs.envoi.categorie.adresseRefusee:adresse refusée`;
    case 'TEMPORAIRE':
      return $localize`:@@animateurs.envoi.categorie.temporaire:refus temporaire`;
    default:
      return $localize`:@@animateurs.envoi.categorie.autre:autre erreur`;
  }
}

/**
 * One sentence about the last mail: « Dernier courriel en échec le … :
 * adresse refusée », « … parti le … », or « … simulé le … » on a server whose
 * mailer is mocked.
 */
export function lastDeliveryLabel(
  envoi: LastMailDelivery,
  mailMock: boolean,
  locale: string,
): string {
  const date = new Date(envoi.le).toLocaleString(locale);
  if (envoi.statut === 'ECHEC') {
    const categorie = failureCategoryLabel(envoi.categorie);
    const echec = $localize`:@@animateurs.envoi.echec:Dernier courriel en échec le ${date}:date: : ${categorie}:categorie:`;
    return envoi.ficheModifieeDepuis
      ? echec +
          $localize`:@@animateurs.envoi.ficheModifiee: — fiche modifiée depuis : le prochain envoi dira si l'adresse est bonne`
      : echec;
  }
  return mailMock
    ? $localize`:@@animateurs.envoi.simule:Dernier courriel simulé le ${date}:date: (envoi désactivé sur ce serveur)`
    : $localize`:@@animateurs.envoi.parti:Dernier courriel parti le ${date}:date:`;
}

/** The failure in a few words for a table cell: « 12/07/2026 · adresse refusée ». */
export function failureDetail(envoi: LastMailDelivery, locale: string): string {
  return `${new Date(envoi.le).toLocaleDateString(locale)} · ${failureCategoryLabel(envoi.categorie)}`;
}
