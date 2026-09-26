// « Dupliquer » on a referential row: the create form, opened on a copy of
// the row. What makes the copy a new row is the same on every page — no id
// (the server draws one), no code (unique within the edition), no write
// stamp (nothing to be concurrent with) — and the name says it is a copy, so
// two identical rows never sit side by side unnoticed.

/** « Buvette (copie) ». Called from a method, never at module scope. */
export function copyName(name: string | null | undefined): string {
  const base = (name ?? '').trim();
  return base ? $localize`:@@duplicate.name:${base}:nom: (copie)` : '';
}
