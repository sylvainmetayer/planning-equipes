// The tabs of the Stands page, read from `?onglet=`: pure, so the menu's
// spec checks the palette's « Stands › Lieux » against the page's own reader.

/** The tabs of the page: the stands, the places they stand on, the game categories they offer. */
export type OngletStands = 'stands' | 'lieux' | 'typologies';

/** `?onglet=lieux` or `?onglet=typologies`; anything else is the stands, the default. */
export function readOngletStands(value: string | null): OngletStands {
  return value === 'lieux' || value === 'typologies' ? value : 'stands';
}
