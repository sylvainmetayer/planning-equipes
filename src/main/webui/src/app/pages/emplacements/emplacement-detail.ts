// What the "consultation" view of an emplacement shows, as a pure builder so
// its content is unit-tested without rendering.

import { DetailSection } from '../../shared/detail-dialog';
import { Emplacement, Stand } from '../../core/models';

/**
 * Identity and coordinates, plus what the edit form cannot show: which stands
 * are tied to this place. That link is the whole point of an emplacement —
 * it is what makes `eviterChangementEmplacementEloigne` bite.
 */
export function buildEmplacementDetail(
  emplacement: Emplacement,
  stands: readonly Stand[] = [],
): DetailSection[] {
  const rattaches = stands
    .filter((stand) => stand.emplacement?.id === emplacement.id)
    .map((stand) => stand.nom || stand.id)
    .sort((left, right) => left.localeCompare(right));
  const geolocalise = emplacement.latitude != null && emplacement.longitude != null;

  return [
    {
      title: $localize`:@@detail.section.identity:Identité`,
      rows: [
        { label: $localize`:@@common.id:Id`, value: emplacement.id },
        {
          label: $localize`:@@referentiel.field.code:Code`,
          value: emplacement.code || $localize`:@@detail.none:Aucun`,
          muted: !emplacement.code,
        },
        { label: $localize`:@@common.nom:Nom`, value: emplacement.nom },
      ],
    },
    {
      title: $localize`:@@detail.section.coordonnees:Coordonnées`,
      rows: [
        {
          label: $localize`:@@emplacements.field.latitude:Latitude`,
          value: geolocalise ? emplacement.latitude!.toFixed(5) : $localize`:@@detail.none:Aucun`,
          muted: !geolocalise,
        },
        {
          label: $localize`:@@emplacements.field.longitude:Longitude`,
          value: geolocalise ? emplacement.longitude!.toFixed(5) : $localize`:@@detail.none:Aucun`,
          muted: !geolocalise,
        },
        ...(geolocalise
          ? []
          : [
              {
                label: $localize`:@@detail.emplacement.effet:Effet sur le planning`,
                value: $localize`:@@detail.emplacement.sansCoordonnees:Sans coordonnées, la règle sur les déplacements lointains ne s'applique pas à ces stands`,
                muted: true,
              },
            ]),
      ],
    },
    {
      title: $localize`:@@detail.emplacement.stands:Stands rattachés`,
      rows: [
        rattaches.length > 0
          ? {
              label: $localize`:@@detail.emplacement.standsCount:${rattaches.length}:count: stand(s)`,
              chips: rattaches,
            }
          : {
              label: $localize`:@@detail.emplacement.stands:Stands rattachés`,
              value: $localize`:@@detail.none:Aucun`,
              muted: true,
            },
      ],
    },
  ];
}
