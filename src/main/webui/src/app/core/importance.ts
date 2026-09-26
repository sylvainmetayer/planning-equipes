// The importance of a rule, as « Règles du planning » offers it: three
// positions — faible, normale, forte — and the weights they write (ADR 0057).
// Pure and in `core/` rather than in the page, because two screens ask it the
// same question: the Règles page draws the three positions, and the
// Diagnostic hides « Baisser l'importance » on a rule already at the bottom —
// the button that led to a field reading 1, twelve times out of twelve.

/** A position of the three, or `null` for a weight set to a value of its own. */
export type Importance = 'FAIBLE' | 'NORMALE' | 'FORTE';

/** The weight each position writes. Mirrors `ConstraintCatalog.POIDS_FAIBLE/NORMAL/FORT`. */
export const POIDS_IMPORTANCE: Readonly<Record<Importance, number>> = {
  FAIBLE: 1,
  NORMALE: 5,
  FORTE: 25,
};

/** The three positions, lowest first — the order the screen lays them out in. */
export const IMPORTANCES: readonly Importance[] = ['FAIBLE', 'NORMALE', 'FORTE'];

/** Lowest weight the server accepts: zero is refused, switching off goes through the toggle. */
export const POIDS_MIN = 1;

/** Highest weight the server accepts — mirrors `ParametresValidator.CONSTRAINT_WEIGHT_MAX`. */
export const POIDS_MAX = 500;

/** The position a weight stands on, or `null` when it is « personnalisé » — any other value. */
export function importanceOf(poids: number): Importance | null {
  return IMPORTANCES.find((importance) => POIDS_IMPORTANCE[importance] === poids) ?? null;
}

/**
 * Whether a rule's importance cannot go any lower: its weight is at the
 * minimum the server accepts. « Baisser l'importance » is then a button that
 * leads nowhere, and a screen offering it should not.
 */
export function importanceAtMinimum(rule: { poids: number }): boolean {
  return rule.poids <= POIDS_MIN;
}

/**
 * A typed weight brought into the range the server accepts; `fallback` for
 * something that is not a number — an empty field, a stray letter.
 */
export function clampWeight(value: number, fallback: number): number {
  if (!Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(Math.max(Math.round(value), POIDS_MIN), POIDS_MAX);
}
