// What the "consultation" view of an animateur shows, as a pure builder so its
// content is unit-tested without rendering — same split as
// `animateur-bulk-edit.ts`.

import { DetailSection } from '../../shared/detail-dialog';
import { Animateur, TypologieItem } from '../../core/models';

/**
 * Identity, statut and the two lists that drive the solver: the
 * administrator's appreciation per typologie, and the declared wishes. Minor /
 * adult is derived from the birth date at {@code aujourdHui} and never stored
 * — the caller passes the reference date so the rule stays testable.
 */
export function buildAnimateurDetail(
  animateur: Animateur,
  typologies: readonly TypologieItem[] = [],
  aujourdHui: Date = new Date()
): DetailSection[] {
  const labels = new Map(typologies.map((typologie) => [typologie.id, typologie.label || typologie.id]));
  const aucun = $localize`:@@detail.none:Aucun`;
  const competences = Object.entries(animateur.competences ?? {});
  const souhaits = animateur.souhaits ?? [];
  const indisponibilites = [...(animateur.joursIndisponibles ?? [])].sort();

  return [
    {
      title: $localize`:@@detail.section.identity:Identité`,
      rows: [
        { label: $localize`:@@common.id:Id`, value: animateur.id },
        {
          label: $localize`:@@common.nom:Nom`,
          value: `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id
        },
        {
          label: $localize`:@@animateurs.field.dateNaissance:Date de naissance`,
          value: animateur.dateNaissance ?? aucun,
          muted: !animateur.dateNaissance
        },
        { label: $localize`:@@animateurs.column.majorite:Majeur`, value: majoriteLabel(animateur, aujourdHui) },
        { label: $localize`:@@animateurs.column.manager:Manager`, value: ouiNon(animateur.manager) },
        {
          label: $localize`:@@animateurs.field.email:E-mail`,
          value: animateur.email || aucun,
          muted: !animateur.email
        },
        {
          label: $localize`:@@animateurs.field.lienEspace:Lien espace animateur`,
          value: animateur.jetonAcces
            ? `/animateur/${animateur.jetonAcces}`
            : aucun,
          muted: !animateur.jetonAcces
        }
      ]
    },
    {
      title: $localize`:@@detail.section.competences:Appréciation`,
      rows: [
        competences.length > 0
          ? {
              label: $localize`:@@animateurs.appreciation.title:Appréciation`,
              chips: competences
                .map(([id, niveau]) => `${labels.get(id) ?? id} · ${niveau}`)
                .sort((left, right) => left.localeCompare(right))
            }
          : {
              label: $localize`:@@animateurs.appreciation.title:Appréciation`,
              value: $localize`:@@detail.animateur.aucuneAppreciation:Aucune appréciation : le solveur peut l'affecter partout, avec une pénalité de qualité`,
              muted: true
            },
        souhaits.length > 0
          ? {
              label: $localize`:@@animateurs.souhaits.title:Souhaits`,
              chips: souhaits.map((id) => labels.get(id) ?? id)
            }
          : { label: $localize`:@@animateurs.souhaits.title:Souhaits`, value: aucun, muted: true }
      ]
    },
    {
      title: $localize`:@@animateurs.column.indisponibilites:Indisponibilités`,
      rows: [
        indisponibilites.length > 0
          ? {
              label: $localize`:@@detail.animateur.joursIndisponibles:Jours indisponibles (${indisponibilites.length}:count:)`,
              chips: indisponibilites
            }
          : {
              label: $localize`:@@animateurs.column.indisponibilites:Indisponibilités`,
              value: $localize`:@@detail.animateur.toujoursDisponible:Disponible tous les jours`,
              muted: true
            }
      ]
    }
  ];
}

/** Mirrors the backend rule: derived from the birth date, never stored. */
function majoriteLabel(animateur: Animateur, aujourdHui: Date): string {
  if (!animateur.dateNaissance) {
    return $localize`:@@detail.unknown:Inconnu`;
  }
  const [year, month, day] = animateur.dateNaissance.split('-').map(Number);
  if (!year || !month || !day) {
    return $localize`:@@detail.unknown:Inconnu`;
  }
  let age = aujourdHui.getFullYear() - year;
  if (aujourdHui.getMonth() + 1 < month || (aujourdHui.getMonth() + 1 === month && aujourdHui.getDate() < day)) {
    age -= 1;
  }
  return age >= 18
    ? $localize`:@@detail.animateur.majeur:Majeur (${age}:age: ans)`
    : $localize`:@@detail.animateur.mineur:Mineur (${age}:age: ans)`;
}

function ouiNon(value: boolean): string {
  return value ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`;
}
