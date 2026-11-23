// The tabs of the Calendrier page (`/creneaux`), read from `?onglet=`: pure,
// so the menu's spec checks the palette's entries against the page's reader.

/** The grid of timeslots, and the stands' opening hours over it. */
export type OngletCalendrier = 'creneaux' | 'horaires';

/** `?onglet=horaires` for the opening hours; anything else is the timeslots, the default. */
export function readOngletCalendrier(value: string | null): OngletCalendrier {
  return value === 'horaires' ? 'horaires' : 'creneaux';
}
