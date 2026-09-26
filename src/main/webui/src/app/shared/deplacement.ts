// Drag-and-drop of one assignment on the day views (issue #308): the pure
// half — what a drop lands on, and how to say what the server did. The
// directives and the HTTP call live in the pages; this is what gets unit
// tested without a DOM.

import { DeplacementSimulation, HardMediumSoftScore, PosteAffectation } from '../core/models';
import { animateurName } from './affectation-explanation-rules';
import { OptionSelection } from './selection-recherche';

/**
 * The seat a drop targets on a stand line: the seat under the pointer when
 * there is one (`data-poste-id` on both the held names and the free-seat
 * placeholders), the line's first free seat otherwise, `null` when the line
 * is full and the pointer landed on no one — the caller then says so rather
 * than guessing whom to swap with.
 */
export function cibleDepot(
  sousLePointeur: Element | null,
  postesLibres: readonly PosteAffectation[],
  postesTenus: readonly PosteAffectation[],
): string | null {
  const vise = sousLePointeur?.closest<HTMLElement>('[data-poste-id]')?.dataset['posteId'] ?? null;
  const connus = new Set([...postesLibres, ...postesTenus].map((poste) => poste.id));
  if (vise && connus.has(vise)) {
    return vise;
  }
  return postesLibres[0]?.id ?? null;
}

export function scoreLabel(score: HardMediumSoftScore): string {
  return `${score.hardScore}/${score.mediumScore}/${score.softScore}`;
}

/** The snack bar of a drop that went through: who went where, and what it cost or saved. */
export function resumeDeplacement(
  simulation: DeplacementSimulation,
  nomDe: (animateurId: string) => string,
): { title: string; message: string } {
  const source = simulation.animateurSourceId ? nomDe(simulation.animateurSourceId) : '';
  const target = simulation.animateurCibleId ? nomDe(simulation.animateurCibleId) : null;
  let title: string;
  if (!simulation.animateurSourceId) {
    // A placement: the seat held nobody.
    const placed = target ?? '';
    title = $localize`:@@deplacement.place:${placed}:cible: est placé(e) sur ce siège.`;
  } else if (simulation.posteCibleId && target) {
    title = $localize`:@@deplacement.echange:${source}:source: et ${target}:cible: ont échangé leurs sièges.`;
  } else if (simulation.posteCibleId) {
    title = $localize`:@@deplacement.deplace:${source}:source: a changé de siège ; l'ancien reste libre.`;
  } else {
    title = $localize`:@@deplacement.attribue:${target}:cible: prend le siège de ${source}:source:.`;
  }
  const message = $localize`:@@deplacement.score:Score : ${scoreLabel(simulation.scoreAvant)}:avant: → ${scoreLabel(simulation.scoreApres)}:apres:.`;
  return { title, message };
}

/**
 * Where « Déplacer vers » may send the holder of `source`: every other stand
 * line of the same day — its free seats to move into, its people to swap with
 * — as the drop of the day calendar accepts them. A line a lock closes
 * (`lineClosed`, for its stand or its timeslot) is left out, as the drop
 * refuses it; so is the source's own line. Ordered as the day reads: the
 * timeslot's start, the stand's name, the seat's own window; free seats
 * before held ones on a line.
 */
export function moveTargets(
  postes: readonly PosteAffectation[],
  source: PosteAffectation,
  lineClosed: (poste: PosteAffectation) => boolean,
): OptionSelection[] {
  const day = source.creneau?.jour;
  const lineKey = (poste: PosteAffectation): string =>
    [
      poste.creneau?.id,
      poste.stand?.id,
      poste.heureDebutEffective ?? poste.creneau?.heureDebut,
      poste.heureFinEffective ?? poste.creneau?.heureFin,
    ].join('::');
  const sourceLine = lineKey(source);
  const candidates = postes.filter(
    (poste) =>
      poste.creneau &&
      poste.stand &&
      poste.creneau.jour === day &&
      poste.id !== source.id &&
      lineKey(poste) !== sourceLine &&
      !lineClosed(poste),
  );
  const sortKey = (poste: PosteAffectation): string[] => [
    poste.creneau?.heureDebut ?? '',
    poste.stand?.nom || poste.stand?.id || '',
    poste.heureDebutEffective ?? poste.creneau?.heureDebut ?? '',
  ];
  candidates.sort((left, right) => {
    const [a, b] = [sortKey(left), sortKey(right)];
    return (
      a[0].localeCompare(b[0]) ||
      a[1].localeCompare(b[1]) ||
      a[2].localeCompare(b[2]) ||
      Number(!!left.animateur) - Number(!!right.animateur)
    );
  });
  return candidates.map((poste) => {
    const heures = `${(poste.creneau?.heureDebut ?? '').slice(0, 5)}–${(poste.creneau?.heureFin ?? '').slice(0, 5)}`;
    const stand = poste.stand?.nom || poste.stand?.id || '';
    if (poste.animateur) {
      const nom = animateurName(poste.animateur);
      return {
        id: poste.id,
        label: $localize`:@@calendarDay.deplacer.echange:${heures}:heures: · ${stand}:stand: — échanger avec ${nom}:nom:`,
      };
    }
    return {
      id: poste.id,
      label: $localize`:@@calendarDay.deplacer.libre:${heures}:heures: · ${stand}:stand: — siège libre`,
    };
  });
}
