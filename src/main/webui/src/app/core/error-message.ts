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
