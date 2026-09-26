// `?ids=a,b,c`: a referential list narrowed to the rows an import just wrote.
// The import card's « Voir les N lignes importées » sets it; the list shows
// the filter as such and « Tout afficher » drops it. Pure, like the other
// readers of the view state in the URL.

/** The query param carrying the ids, comma-separated. */
export const IMPORTED_IDS_PARAM = 'ids';

/** The ids the address names, `null` when it names none: no filter at all, not an empty list. */
export function readImportedIds(value: string | null | undefined): ReadonlySet<string> | null {
  const ids = (value ?? '')
    .split(',')
    .map((id) => id.trim())
    .filter((id) => id !== '');
  return ids.length === 0 ? null : new Set(ids);
}

/** The inverse of {@link readImportedIds}: no filter clears the param. */
export function importedIdsParam(ids: ReadonlySet<string> | null): string | null {
  return ids === null || ids.size === 0 ? null : [...ids].join(',');
}

/** Whether a row stays under the filter: every row does when there is none. */
export function keptByImportedIds(ids: ReadonlySet<string> | null, id: string | number): boolean {
  return ids === null || ids.has(String(id));
}
