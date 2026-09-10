// Quick text filter shared by the reference-data pages (stands, emplacements,
// animateurs, typologies): "does this row match what was typed?".
//
// Kept as plain functions, outside any component, so the matching rules are
// unit-tested without rendering a table — and so the four pages cannot drift
// apart on what "matching" means.

/**
 * Lower-cased, accent-stripped form used on both sides of the comparison:
 * typing `mediatheque` has to find « Médiathèque », and typing `MÉDIA` too.
 */
export function normaliserPourFiltre(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

/**
 * Splits what the user typed into the terms every matching row must carry.
 * Whitespace-separated, so `village enf` finds the stand named « Village des
 * Enfants » whichever order the words appear in the row.
 */
export function termesDuFiltre(query: string): string[] {
  return normaliserPourFiltre(query)
    .split(/\s+/)
    .filter((terme) => terme.length > 0);
}

/**
 * True when every term of `query` appears somewhere in `champs`. An empty (or
 * blank) query matches everything, so a page can filter unconditionally
 * instead of branching on "is a filter active".
 *
 * <p>Terms are AND-ed, and matched against the concatenation of the fields
 * rather than field by field: a row is looked up by whatever the user
 * remembers of it (an id, a name, a location), not by one designated column.</p>
 */
export function correspondAuFiltre(
  query: string,
  champs: readonly (string | number | null | undefined)[],
): boolean {
  const termes = termesDuFiltre(query);
  if (termes.length === 0) {
    return true;
  }
  const haystack = normaliserPourFiltre(
    champs.filter((champ) => champ !== null && champ !== undefined && champ !== '').join(' '),
  );
  return termes.every((terme) => haystack.includes(terme));
}
