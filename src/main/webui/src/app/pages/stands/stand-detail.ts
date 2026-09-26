// The words of a stand's schedule — its rules, its dated exceptions, its
// anomalies —, read on the fiche stand and by the openings comparator. Kept out
// of the components so they are unit-tested without rendering anything — same
// split as `stand-bulk-edit.ts`.

import { DetailRow } from '../../shared/detail-dialog';
import { decrireFenetre, resumerHoraires } from '../../core/horaire-stand';
import {
  AnomalieOuverture,
  HoraireStand,
  IndisponibiliteStand,
  JourSemaine,
  OuvertureStand,
  Stand,
} from '../../core/models';

/**
 * The schedule of a stand in lines: the summary, then each recurring rule
 * spelled out and each dated exception — what the « Règles » section of the
 * fiche stand shows, the condensed form of its grid.
 */
export function scheduleRows(stand: Stand): DetailRow[] {
  return [
    {
      label: $localize`:@@stands.column.horaires:Horaires`,
      value: resumerHoraires(stand, {
        aucun: $localize`:@@detail.stand.horairesAucun:Ouvert par défaut, aucune règle`,
        regles: (n) => $localize`:@@stands.horaires.summary.regles:${n}:count: règle(s)`,
        exceptions: (n) =>
          $localize`:@@stands.horaires.summary.exceptions:${n}:count: exception(s)`,
      }),
    },
    // Each rule spelled out, not just counted: "2 règles" says nothing about
    // when the stand is actually open.
    ...(stand.horaires ?? []).map((horaire, index) => ({
      label: $localize`:@@detail.stand.regle:Règle ${index + 1}:numero:`,
      value: describeRule(horaire),
    })),
    ...(stand.ouvertures ?? []).map((ouverture) => describeException(ouverture, true)),
    ...(stand.indisponibilites ?? []).map((indisponibilite) =>
      describeException(indisponibilite, false),
    ),
  ];
}

/** What an anomaly of the openings is about: the day it falls on, or how the rules are written. */
export function anomalyLabel(anomalie: AnomalieOuverture): string {
  switch (anomalie.type) {
    case 'REGLES_CHEVAUCHANTES':
      return $localize`:@@detail.stand.anomalie.reglesChevauchantes:Règles qui se recouvrent`;
    case 'REGLE_MASQUEE':
      return $localize`:@@detail.stand.anomalie.regleMasquee:Règle sans effet`;
    case 'FENETRES_CHEVAUCHANTES':
      if (anomalie.date) {
        const jour = jourCourt(anomalie.date);
        return $localize`:@@detail.stand.anomalie.fenetresChevauchantesLe:Fenêtres qui se recouvrent, ${jour}:jour:`;
      }
      return $localize`:@@detail.stand.anomalie.fenetresChevauchantes:Fenêtres qui se recouvrent`;
    default:
      return anomalie.date
        ? jourCourt(anomalie.date)
        : $localize`:@@detail.stand.anomalie.edition:Toute l'édition`;
  }
}

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
      return (horaire.dates ?? []).map(jourCourt).join(', ');
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

/** `08/07` rather than `2026-07-08`: a rule can list a dozen dates on one line. */
function jourCourt(date: string): string {
  const [, mois, jour] = date.split('-');
  return jour && mois ? `${jour}/${mois}` : date;
}

function libelleJourSemaine(jour: JourSemaine): string {
  switch (jour) {
    case 'MONDAY':
      return $localize`:@@common.weekday.monday:Lundi`;
    case 'TUESDAY':
      return $localize`:@@common.weekday.tuesday:Mardi`;
    case 'WEDNESDAY':
      return $localize`:@@common.weekday.wednesday:Mercredi`;
    case 'THURSDAY':
      return $localize`:@@common.weekday.thursday:Jeudi`;
    case 'FRIDAY':
      return $localize`:@@common.weekday.friday:Vendredi`;
    case 'SATURDAY':
      return $localize`:@@common.weekday.saturday:Samedi`;
    default:
      return $localize`:@@common.weekday.sunday:Dimanche`;
  }
}
