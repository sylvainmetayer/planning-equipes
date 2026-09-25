/**
 * Derives a stable id from a free-typed name (uppercased, accents stripped,
 * non-alphanumeric runs collapsed to a dash), suffixed with -2, -3... until
 * it doesn't collide with `existingIds`. Used where the user only types a
 * name (e.g. deriving an id from a typed label) but the backend still
 * needs an explicit primary key.
 */
export function slugify(nom: string, existingIds: Iterable<string>): string {
  const base =
    nom
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toUpperCase()
      .replace(/[^A-Z0-9]+/g, '-')
      // The runs are already collapsed to a single dash: at most one per end.
      .replace(/^-|-$/g, '') || 'GROUPE';
  const existing = new Set(existingIds);
  let id = base;
  let suffix = 2;
  while (existing.has(id)) {
    id = `${base}-${suffix}`;
    suffix++;
  }
  return id;
}
