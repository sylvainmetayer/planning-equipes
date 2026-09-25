// Turning a caught `unknown` into something displayable, in one place.
//
// Every `catch` in this application faces the same problem: TypeScript types a
// caught value as `unknown`, so it has to be narrowed before it can be shown.
// The narrowing was written by hand ~38 times, and the helper doing it was
// copy-pasted verbatim into 7 pages.

/**
 * The human-readable part of a caught value. `Error` is the normal case (the
 * API layer normalises server failures into one — see `toError`); anything
 * else is stringified rather than dropped, because a thrown string or a
 * rejected promise carrying a plain object still has to say something.
 */
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * The same message behind the shared "Erreur : …" prefix, for the result
 * panels and inline error lines.
 *
 * Called lazily (never at module scope): `$localize` only sees the catalogue
 * registered by `loadTranslations()` in `main.ts`, which runs after this
 * module is imported but before any of these calls execute.
 */
export function errorPrefix(error: unknown): string {
  const message = errorMessage(error);
  return $localize`:@@common.errorPrefix:Erreur : ${message}:message:`;
}

/** The `code` a 409 carries when the write touches a frozen family of the referential (ADR 0052). */
export const CODE_FROZEN_REFERENTIAL = 'REFERENTIEL_FIGE';

/**
 * Whether a caught value is the refusal of a frozen referential — the one 409
 * after which the screen re-reads the freeze, since another session may have
 * laid it down since this one loaded its padlocks.
 */
export function isFrozenReferential(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { code?: unknown }).code === CODE_FROZEN_REFERENTIAL
  );
}
