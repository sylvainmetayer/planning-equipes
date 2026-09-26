// The two tabs of the Stands page, read from `?onglet=`: pure, so the menu's
// spec checks the palette's « Stands › Lieux » against the page's own reader.

/** The two tabs of the page: the stands, and the places they stand on. */
export type OngletStands = 'stands' | 'lieux';

/** `?onglet=lieux` for the places; anything else is the stands, the default. */
export function readOngletStands(value: string | null): OngletStands {
  return value === 'lieux' ? 'lieux' : 'stands';
}
