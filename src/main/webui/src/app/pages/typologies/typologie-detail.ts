// What the "consultation" view of a typologie shows, as a pure builder so its
// content is unit-tested without rendering.

import { DetailSection } from '../../shared/detail-dialog';
import { Animateur, Stand, TypologieItem } from '../../core/models';

/**
 * A typologie is only ever meaningful through what references it: the stands
 * proposing it and the animateurs the administrator vetted on it. A typologie
 * nobody masters, or one no stand proposes, is exactly the kind of thing this
 * view is opened to spot — so both counts are spelled out, empty or not.
 */
export function buildTypologieDetail(
  typologie: TypologieItem,
  stands: readonly Stand[] = [],
  animateurs: readonly Animateur[] = []
): DetailSection[] {
  const standsProposant = stands
    .filter((stand) => (stand.typologiesProposees ?? []).includes(typologie.id))
    .map((stand) => stand.nom || stand.id)
    .sort((left, right) => left.localeCompare(right));
  const animateursCompetents = animateurs
    .filter((animateur) => typologie.id in (animateur.competences ?? {}))
    .map((animateur) => `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id)
    .sort((left, right) => left.localeCompare(right));

  return [
    {
      title: $localize`:@@detail.section.identity:Identité`,
      rows: [
        { label: $localize`:@@common.id:Id`, value: typologie.id },
        { label: $localize`:@@typologies.field.label:Libellé`, value: typologie.label || typologie.id },
        {
          label: $localize`:@@typologies.field.ninja:Typologie ninja`,
          value: typologie.ninja ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`
        }
      ]
    },
    {
      title: $localize`:@@detail.typologie.usage:Utilisation`,
      rows: [
        standsProposant.length > 0
          ? {
              label: $localize`:@@detail.typologie.stands:Stands proposant cette typologie (${standsProposant.length}:count:)`,
              chips: standsProposant
            }
          : {
              label: $localize`:@@detail.typologie.standsVide:Stands proposant cette typologie`,
              value: $localize`:@@detail.none:Aucun`,
              muted: true
            },
        animateursCompetents.length > 0
          ? {
              label: $localize`:@@detail.typologie.animateurs:Animateurs appréciés sur cette typologie (${animateursCompetents.length}:count:)`,
              chips: animateursCompetents
            }
          : {
              label: $localize`:@@detail.typologie.animateursVide:Animateurs appréciés sur cette typologie`,
              value: $localize`:@@detail.none:Aucun`,
              muted: true
            }
      ]
    }
  ];
}
