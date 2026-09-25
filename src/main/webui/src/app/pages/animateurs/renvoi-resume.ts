// « Renvoyer les envois en échec »: who the button concerns, read from the
// rows the page already holds, and the wording of its report — by name in the
// snack bar, counted in the persisted journal, on the model of
// `relance-resume.ts` and for the same reason (`docs/rgpd.md` §7).

import type { ConfirmationView, MotifNonRenvoi, RapportRenvoi } from '../../core/models';
import type { ResumeRelance } from './relance-resume';

/** Beyond this many names the snack bar is unreadable anyway, and says how many more. */
const NOMS_MAX = 8;

/**
 * The people whose last mail failed for a temporary reason: what the button
 * would send again. A refused address is not one of them — it stays blocked
 * until the address changes, and resending would only earn the same refusal.
 */
export function temporaryFailures(confirmations: Iterable<ConfirmationView>): number {
  let count = 0;
  for (const confirmation of confirmations) {
    const envoi = confirmation.dernierEnvoi;
    if (envoi?.statut === 'ECHEC' && envoi.categorieEchec !== 'ADRESSE_REFUSEE') {
      count++;
    }
  }
  return count;
}

/** Why somebody was left alone, in the organiser's words. */
function motif(raison: MotifNonRenvoi): string {
  switch (raison) {
    case 'TYPE_NON_RENVOYABLE':
      return $localize`:@@animateurs.renvoyer.motif.type:code d'accès ou échange, à redemander`;
    case 'JAMAIS_PUBLIE':
      return $localize`:@@animateurs.renvoyer.motif.jamaisPublie:rien de publié`;
    case 'SANS_POSTE':
      return $localize`:@@animateurs.renvoyer.motif.sansPoste:sans poste au planning publié`;
    case 'DEJA_CONFIRME':
      return $localize`:@@animateurs.renvoyer.motif.dejaConfirme:déjà confirmé`;
    case 'DEJA_RELANCE':
      return $localize`:@@animateurs.renvoyer.motif.dejaRelance:déjà relancé`;
    case 'COLLECTE_FERMEE':
      return $localize`:@@animateurs.renvoyer.motif.collecteFermee:collecte fermée`;
    case 'SANS_ADRESSE':
      return $localize`:@@animateurs.renvoyer.motif.sansAdresse:sans adresse`;
    case 'ADRESSE_REFUSEE':
      return $localize`:@@animateurs.renvoyer.motif.adresseRefusee:adresse refusée`;
  }
}

/**
 * Folds the report into a title and its details. `nomDe` turns an id into
 * what the table shows, and answers the id itself for a fiche deleted in
 * between.
 */
export function resumeRenvoi(
  rapport: RapportRenvoi,
  nomDe: (animateurId: string) => string,
): ResumeRelance {
  const liste = (elements: string[]) => {
    const affiches = elements.slice(0, NOMS_MAX).join(', ');
    const restants = elements.length - NOMS_MAX;
    return restants > 0
      ? affiches + $localize`:@@animateurs.renvoyer.autres: et ${restants}:count: autre(s)`
      : affiches;
  };
  const titre = $localize`:@@animateurs.renvoyer.resume:${rapport.renvoyes.length}:count: envoi(s) reparti(s)`;
  const details: string[] = [];
  if (rapport.echecs.length > 0) {
    details.push(
      $localize`:@@animateurs.renvoyer.echecs:Nouvel échec : ${liste(rapport.echecs.map(nomDe))}:noms:`,
    );
  }
  if (rapport.nonRenvoyables.length > 0) {
    const lignes = liste(
      rapport.nonRenvoyables.map((each) => `${nomDe(each.animateurId)} (${motif(each.motif)})`),
    );
    details.push($localize`:@@animateurs.renvoyer.nonRenvoyables:Non renvoyés : ${lignes}:lignes:`);
  }
  return {
    titre,
    details: details.length > 0 ? details.join(' — ') : undefined,
    detailsJournal:
      details.length > 0
        ? $localize`:@@animateurs.renvoyer.journal:${rapport.echecs.length}:echecs: en échec, ${rapport.nonRenvoyables.length}:nonRenvoyables: non renvoyé(s)`
        : undefined,
    variant: rapport.echecs.length > 0 ? 'warning' : 'success',
  };
}
