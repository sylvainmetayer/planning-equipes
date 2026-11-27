// The words of a stand's schedule — its rules and its dated exceptions —, read
// on the fiche stand and in the stand dialogs. Kept out
// of the components so they are unit-tested without rendering anything — same
// split as `stand-bulk-edit.ts`.

import { DetailRow } from '../../shared/detail-dialog';
import { decrireFenetre, libelleJour, libelleJourSemaine } from '../../core/horaire-stand';
import { HoraireStand, IndisponibiliteStand, OuvertureStand } from '../../core/models';

/**
 * One recurring rule in one line: what it does, which days it covers, and the
 * windows it opens or closes — the three parts a rule is made of, in the order
 * the editor asks for them.
 */
export function describeRule(horaire: HoraireStand): string {
  const mode =
    horaire.mode === 'OUVERTURE'
      ? $localize`:@@detail.stand.mode.ouverture:Ouverture`
      : $localize`:@@detail.stand.mode.fermeture:Fermeture`;
  const fenetres = (horaire.fenetres ?? [])
    .map((fenetre) => decrireFenetre(fenetre, $localize`:@@stands.apercu.fermeture:fermeture`))
    .join(', ');
  const morceaux = [mode, describeDays(horaire), fenetres].filter((morceau) => morceau.length > 0);
  const description = morceaux.join(' · ');
  return horaire.motif ? `${description} — ${horaire.motif}` : description;
}

/** The day selector, read through whichever field `jours` designates — the others are ignored, exactly like the backend does. */
export function describeDays(horaire: HoraireStand): string {
  switch (horaire.jours) {
    case 'JOURS_SEMAINE':
      return (horaire.joursSemaine ?? []).map(libelleJourSemaine).join(', ');
    case 'PLAGE':
      return $localize`:@@detail.stand.plage:du ${horaire.dateDebut ?? '?'}:debut: au ${horaire.dateFin ?? '?'}:fin:`;
    case 'DATES':
      return (horaire.dates ?? []).map(libelleJour).join(', ');
    default:
      return $localize`:@@stands.horaires.jours.tous:Tous les jours`;
  }
}

export function describeException(
  exception: OuvertureStand | IndisponibiliteStand,
  ouverture: boolean,
): DetailRow {
  const fenetre = decrireFenetre(
    {
      heureDebut: exception.heureDebut,
      heureFin: exception.heureFin,
      effectif: ouverture ? ((exception as OuvertureStand).effectif ?? null) : null,
    },
    $localize`:@@stands.apercu.fermeture:fermeture`,
  );
  return {
    label: ouverture
      ? $localize`:@@detail.stand.ouvertureDatee:Ouverture du ${exception.date}:date:`
      : $localize`:@@detail.stand.fermetureDatee:Fermeture du ${exception.date}:date:`,
    value: exception.motif ? `${fenetre} — ${exception.motif}` : fenetre,
  };
}
