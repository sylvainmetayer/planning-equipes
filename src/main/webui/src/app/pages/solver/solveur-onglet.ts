// The tabs of the Solveur page, read from `?onglet=`: pure, so the menu's spec
// checks the palette's « Solveur › Versions du plan » against the page's reader.

/** The solve, and the versions of the plan it produced. */
export type OngletSolveur = 'calcul' | 'versions';

/** `?onglet=versions` for the versions; anything else is the solve, the default. */
export function readOngletSolveur(value: string | null): OngletSolveur {
  return value === 'versions' ? 'versions' : 'calcul';
}
