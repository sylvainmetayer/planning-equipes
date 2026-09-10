// The rules behind the "Pourquoi lui ?" popup, kept out of the dialog so they
// are unit tested without rendering — the same convention `stand-draft.ts`
// already follows for the stand form next door (AGENTS.md).
//
// Everything here is a pure function of what the server answered: whether a
// delta is an improvement, whether the search was truncated, which suggestions
// to show and under whose name. The dialog holds the signals; this file holds
// what they mean.

import {
  Animateur,
  HardMediumSoftScore,
  PlanningEvenement,
  Stand,
  SuggestionReparation,
  SuggestionsReparation,
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
  max = MAX_SUGGESTIONS_AFFICHEES,
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
