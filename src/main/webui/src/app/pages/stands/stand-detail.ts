// What the "consultation" view of a stand shows. Kept out of the dialog (and
// out of the page) so the content is unit-tested without rendering anything —
// same split as `stand-bulk-edit.ts`.

import { DetailRow, DetailSection } from '../../shared/detail-dialog';
import { decrireFenetre, resumerHoraires } from '../../core/horaire-stand';
import { HoraireStand, IndisponibiliteStand, JourSemaine, OuvertureStand, Stand, TypologieItem } from '../../core/models';

/**
 * Identity, staffing, schedule and — the reason a detail view is worth more
 * than re-opening the edit form — the things the form does not show: the
 * resolved location, the human labels of the typologies, and how many dated
 * exceptions actually override the recurring rules.
 */
export function buildStandDetail(stand: Stand, typologies: readonly TypologieItem[] = []): DetailSection[] {
  const labels = new Map(typologies.map((typologie) => [typologie.id, typologie.label || typologie.id]));
  const aucun = $localize`:@@detail.none:Aucun`;

  return [
    {
      title: $localize`:@@detail.section.identity:Identité`,
      rows: [
        { label: $localize`:@@common.id:Id`, value: stand.id },
        { label: $localize`:@@common.nom:Nom`, value: stand.nom },
        {
          label: $localize`:@@stands.field.emplacement:Emplacement`,
          value: stand.emplacement ? emplacementLabel(stand) : aucun,
          muted: !stand.emplacement
        }
      ]
    },
    {
      title: $localize`:@@detail.section.staffing:Effectif`,
      rows: [
        {
          label: $localize`:@@stands.column.effectif:Effectif`,
          value: $localize`:@@detail.stand.effectifRange:${stand.effectifMin}:min: à ${stand.effectifMax}:max: animateur(s)`
        },
        {
          label: $localize`:@@stands.field.reserveMajeurs:Réservé aux majeurs`,
          value: ouiNon(stand.reserveMajeurs)
        },
        {
          label: $localize`:@@stands.field.premium:Premium (stand éditeur)`,
          value: ouiNon(stand.premium)
        },
        {
          label: $localize`:@@stands.field.niveauEffort:Épuisant physiquement`,
          value: ouiNon(stand.niveauEffort === 'EPUISANT')
        }
      ]
    },
    {
      title: $localize`:@@detail.section.typologies:Typologies proposées`,
      rows: [
        (stand.typologiesProposees ?? []).length > 0
          ? {
              label: $localize`:@@stands.column.typologies:Typologies`,
              chips: (stand.typologiesProposees ?? []).map((id) => labels.get(id) ?? id)
            }
          : { label: $localize`:@@stands.column.typologies:Typologies`, value: aucun, muted: true }
      ]
    },
    {
      title: $localize`:@@detail.section.horaires:Horaires`,
      rows: [
        {
          label: $localize`:@@stands.column.horaires:Horaires`,
          value: resumerHoraires(stand, {
            aucun: $localize`:@@detail.stand.horairesAucun:Ouvert par défaut, aucune règle`,
            regles: (n) => $localize`:@@stands.horaires.summary.regles:${n}:count: règle(s)`,
            exceptions: (n) => $localize`:@@stands.horaires.summary.exceptions:${n}:count: exception(s)`
          })
        },
        // Each rule spelled out, not just counted: "2 règles" says nothing
        // about when the stand is actually open, which is the one thing this
        // view is opened to check.
        ...(stand.horaires ?? []).map((horaire, index) => ({
          label: $localize`:@@detail.stand.regle:Règle ${index + 1}:numero:`,
          value: decrireHoraire(horaire)
        })),
        ...(stand.ouvertures ?? []).map((ouverture) => decrireExceptionRow(ouverture, true)),
        ...(stand.indisponibilites ?? []).map((indisponibilite) => decrireExceptionRow(indisponibilite, false))
      ]
    }
  ];
}

/**
 * One recurring rule in one line: what it does, which days it covers, and the
 * windows it opens or closes — the three parts a rule is made of, in the order
 * the editor asks for them.
 */
function decrireHoraire(horaire: HoraireStand): string {
  const mode = horaire.mode === 'OUVERTURE'
    ? $localize`:@@detail.stand.mode.ouverture:Ouverture`
    : $localize`:@@detail.stand.mode.fermeture:Fermeture`;
  const fenetres = (horaire.fenetres ?? [])
    .map((fenetre) => decrireFenetre(fenetre, $localize`:@@stands.apercu.fermeture:fermeture`))
    .join(', ');
  const morceaux = [mode, decrireJours(horaire), fenetres].filter((morceau) => morceau.length > 0);
  const description = morceaux.join(' · ');
  return horaire.motif ? `${description} — ${horaire.motif}` : description;
}

/** The day selector, read through whichever field `jours` designates — the others are ignored, exactly like the backend does. */
function decrireJours(horaire: HoraireStand): string {
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

function decrireExceptionRow(exception: OuvertureStand | IndisponibiliteStand, ouverture: boolean): DetailRow {
  const fenetre = decrireFenetre(
    {
      heureDebut: exception.heureDebut,
      heureFin: exception.heureFin,
      effectif: ouverture ? (exception as OuvertureStand).effectif ?? null : null
    },
    $localize`:@@stands.apercu.fermeture:fermeture`
  );
  return {
    label: ouverture
      ? $localize`:@@detail.stand.ouvertureDatee:Ouverture du ${exception.date}:date:`
      : $localize`:@@detail.stand.fermetureDatee:Fermeture du ${exception.date}:date:`,
    value: exception.motif ? `${fenetre} — ${exception.motif}` : fenetre
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

function emplacementLabel(stand: Stand): string {
  const emplacement = stand.emplacement!;
  const nom = emplacement.nom || emplacement.id;
  if (emplacement.latitude == null || emplacement.longitude == null) {
    return nom;
  }
  return `${nom} (${emplacement.latitude.toFixed(5)}, ${emplacement.longitude.toFixed(5)})`;
}

function ouiNon(value: boolean): string {
  return value ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`;
}
