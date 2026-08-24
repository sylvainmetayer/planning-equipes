// The rules behind the "Pourquoi lui ?" popup, kept out of the dialog so they
// are unit tested without rendering — the same convention `stand-draft.ts`
// already follows for the stand form next door (AGENTS.md).
//
// Everything here is a pure function of what the server answered: which
// violations a swap would resolve, which ones it would introduce, whether the
// resulting delta is an improvement, and who may be offered as a candidate.
// The dialog holds the signals; this file holds what they mean.

import {
  Animateur,
  ContrainteImpact,
  HardMediumSoftScore,
  PlanningEvenement,
  PosteAffectation,
  Stand,
  SuggestionReparation,
  SuggestionsReparation,
  SwapSimulation
} from '../core/models';

/** Lexicographic hard > medium > soft comparison, matching how Timefold itself compares scores. */
export function compareDelta(delta: HardMediumSoftScore): 'better' | 'worse' | 'same' {
  if (delta.hardScore !== 0) {
    return delta.hardScore > 0 ? 'better' : 'worse';
  }
  if (delta.mediumScore !== 0) {
    return delta.mediumScore > 0 ? 'better' : 'worse';
  }
  if (delta.softScore !== 0) {
    return delta.softScore > 0 ? 'better' : 'worse';
  }
  return 'same';
}

/** Which way a simulated swap moves the score, `'same'` while no simulation is loaded. */
export function sensDuDelta(simulation: SwapSimulation | null): 'better' | 'worse' | 'same' {
  const delta = simulation?.delta;
  return delta ? compareDelta(delta) : 'same';
}

/**
 * Violations the swap would clear: constraints violated before and no longer
 * violated after. Matched by constraint `name`, the only stable identity the
 * server sends for them.
 */
export function violationsResolues(simulation: SwapSimulation | null): ContrainteImpact[] {
  if (!simulation) {
    return [];
  }
  const nomsApres = new Set(simulation.contraintesVioleesApres.map((impact) => impact.name));
  return simulation.contraintesVioleesAvant.filter((impact) => !nomsApres.has(impact.name));
}

/** The mirror image: violations the swap would introduce. */
export function nouvellesViolations(simulation: SwapSimulation | null): ContrainteImpact[] {
  if (!simulation) {
    return [];
  }
  const nomsAvant = new Set(simulation.contraintesVioleesAvant.map((impact) => impact.name));
  return simulation.contraintesVioleesApres.filter((impact) => !nomsAvant.has(impact.name));
}

/**
 * True when the assistant stopped at its plafond instead of exhausting the
 * eligible pool — the list is then the best of what it saw, and the UI must
 * not present it as the complete answer.
 */
export function suggestionsTronquees(suggestions: SuggestionsReparation | null): boolean {
  return suggestions !== null && suggestions.candidatsEvalues < suggestions.candidatsEligibles;
}

/**
 * The N best suggestions, in the order the server ranked them — it already
 * sorted by impact, so this only trims. Kept as a function rather than a
 * template slice so the cap has one name and one test.
 */
export function meilleuresSuggestions(
  suggestions: SuggestionsReparation | null,
  max = MAX_SUGGESTIONS_AFFICHEES
): SuggestionReparation[] {
  return (suggestions?.suggestions ?? []).slice(0, max);
}

/** How many suggestions the dialog lists: past a handful, the operator scrolls instead of choosing. */
export const MAX_SUGGESTIONS_AFFICHEES = 5;

/**
 * Display name of an animateur the server only names by id — the suggestions
 * carry ids, the planning carries the people. Falls back to the raw id rather
 * than to an empty cell when the referential changed under us.
 */
export function nomAnimateur(planning: PlanningEvenement, animateurId: string): string {
  const animateur = (planning.animateurs ?? []).find((candidat) => candidat.id === animateurId);
  return animateur ? `${animateur.prenom} ${animateur.nom}` : animateurId;
}

/** True when the animateur holds an appreciation on at least one typologie this stand offers. */
export function aUneAppreciationPour(animateur: Animateur, stand: Stand): boolean {
  return stand.typologiesProposees.some((typologie) => typologie in (animateur.competences ?? {}));
}

/**
 * Who the dialog offers as a replacement: every animateur of the planning but
 * the poste's current occupant, keeping only those holding an appreciation for
 * the poste's stand.
 */
export function candidatsPour(planning: PlanningEvenement, poste: PosteAffectation): Animateur[] {
  return (planning.animateurs ?? []).filter(
    (animateur) =>
      animateur.id !== poste.animateur?.id && poste.stand && aUneAppreciationPour(animateur, poste.stand)
  );
}
