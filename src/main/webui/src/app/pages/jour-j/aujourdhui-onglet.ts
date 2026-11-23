// The tabs of the Aujourd'hui page, read from `?onglet=`: pure, so the menu's
// spec checks the palette's « Aujourd'hui › Réalisé vs planifié » against it.

/** The day under way, and the elapsed days read against the publication. */
export type OngletAujourdhui = 'jour' | 'realise';

/** `?onglet=realise` for the gap; anything else is the day, the default. */
export function readOngletAujourdhui(value: string | null): OngletAujourdhui {
  return value === 'realise' ? 'realise' : 'jour';
}
