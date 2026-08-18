// What the "consultation" view of a stand shows. Kept out of the dialog (and
// out of the page) so the content is unit-tested without rendering anything —
// same split as `stand-bulk-edit.ts`.

import { DetailSection } from '../../shared/detail-dialog';
import { resumerHoraires } from '../../core/horaire-stand';
import { Stand, TypologieItem } from '../../core/models';

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
        {
          label: $localize`:@@detail.stand.ouvertures:Ouvertures datées`,
          value: String((stand.ouvertures ?? []).length)
        },
        {
          label: $localize`:@@detail.stand.indisponibilites:Fermetures datées`,
          value: String((stand.indisponibilites ?? []).length)
        }
      ]
    }
  ];
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
