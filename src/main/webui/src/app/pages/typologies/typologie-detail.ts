// What the "consultation" view of a typologie shows, as a pure builder so its
// content is unit-tested without rendering.

import { DetailSection } from '../../shared/detail-dialog';
import { Animateur, LigneTypologie, Stand, TypologieItem } from '../../core/models';

/**
 * A typologie is only ever meaningful through what references it: the stands
 * proposing it and the animateurs the administrator vetted on it. A typologie
 * nobody masters, or one no stand proposes, is exactly the kind of thing this
 * view is opened to spot — so both counts are spelled out, empty or not.
 */
export function buildTypologieDetail(
  typologie: TypologieItem,
  stands: readonly Stand[] = [],
  animateurs: readonly Animateur[] = [],
  affectation: LigneTypologie | null = null,
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
        {
          label: $localize`:@@typologies.field.label:Libellé`,
          value: typologie.label || typologie.id,
        },
        {
          label: $localize`:@@typologies.field.ninja:Typologie ninja`,
          value: typologie.ninja ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`,
        },
        {
          label: $localize`:@@typologies.field.maxCreneaux:Créneaux maximum par animateur`,
          value:
            typologie.maxCreneauxParAnimateur == null
              ? $localize`:@@typologies.detail.sansPlafond:Pas de plafond`
              : String(typologie.maxCreneauxParAnimateur),
          muted: typologie.maxCreneauxParAnimateur == null,
        },
      ],
    },
    {
      title: $localize`:@@detail.typologie.usage:Utilisation`,
      rows: [
        standsProposant.length > 0
          ? {
              label: $localize`:@@detail.typologie.stands:Stands proposant cette typologie (${standsProposant.length}:count:)`,
              chips: standsProposant,
            }
          : {
              label: $localize`:@@detail.typologie.standsVide:Stands proposant cette typologie`,
              value: $localize`:@@detail.none:Aucun`,
              muted: true,
            },
        animateursCompetents.length > 0
          ? {
              label: $localize`:@@detail.typologie.animateurs:Animateurs appréciés sur cette typologie (${animateursCompetents.length}:count:)`,
              chips: animateursCompetents,
            }
          : {
              label: $localize`:@@detail.typologie.animateursVide:Animateurs appréciés sur cette typologie`,
              value: $localize`:@@detail.none:Aucun`,
              muted: true,
            },
      ],
    },
    // Only once the plan has been read on this page: « who is actually on it »
    // is a different question from « who may be », and an empty section would
    // read as « nobody », which is not the same as « nobody asked ».
    ...(affectation
      ? [
          {
            title: $localize`:@@typologies.affectation.title:Qui tient quoi, et pour quel volume`,
            rows: [
              {
                label: $localize`:@@typologies.affectation.column.postes:Postes`,
                value: String(affectation.postes),
              },
              {
                label: $localize`:@@typologies.affectation.column.heures:Heures`,
                value: `${affectation.heures.toFixed(1)} h`,
              },
              affectation.animateursAffectes.length > 0
                ? {
                    label: $localize`:@@typologies.affectation.column.affectes:Animateurs affectés`,
                    chips: affectation.animateursAffectes,
                  }
                : {
                    label: $localize`:@@typologies.affectation.column.affectes:Animateurs affectés`,
                    value: $localize`:@@detail.none:Aucun`,
                    muted: true,
                  },
              ...(affectation.competentsJamaisAffectes.length > 0
                ? [
                    {
                      label: $localize`:@@typologies.detail.jamaisAffectes:Appréciés mais jamais affectés`,
                      chips: affectation.competentsJamaisAffectes,
                    },
                  ]
                : []),
              ...(affectation.affectesSansCompetence.length > 0
                ? [
                    {
                      label: $localize`:@@typologies.detail.sansCompetence:Affectés sans l'appréciation`,
                      chips: affectation.affectesSansCompetence,
                    },
                  ]
                : []),
            ],
          },
        ]
      : []),
  ];
}
