// Drag-and-drop of one assignment on the day views (issue #308): the pure
// half — what a drop lands on, and how to say what the server did. The
// directives and the HTTP call live in the pages; this is what gets unit
// tested without a DOM.

import { DeplacementSimulation, HardMediumSoftScore, PosteAffectation } from '../core/models';

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
  postesTenus: readonly PosteAffectation[]
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
  nomDe: (animateurId: string) => string
): { title: string; message: string } {
  const source = nomDe(simulation.animateurSourceId);
  const target = simulation.animateurCibleId ? nomDe(simulation.animateurCibleId) : null;
  let title: string;
  if (simulation.posteCibleId && target) {
    title = $localize`:@@deplacement.echange:${source}:source: et ${target}:cible: ont échangé leurs sièges.`;
  } else if (simulation.posteCibleId) {
    title = $localize`:@@deplacement.deplace:${source}:source: a changé de siège ; l'ancien reste libre.`;
  } else {
    title = $localize`:@@deplacement.attribue:${target}:cible: prend le siège de ${source}:source:.`;
  }
  const message = $localize`:@@deplacement.score:Score : ${scoreLabel(simulation.scoreAvant)}:avant: → ${scoreLabel(simulation.scoreApres)}:apres:.`;
  return { title, message };
}
