// The pure side of the Imports page: which of the six tabs a query param names.

/** The six tabs, and the values of the `onglet` query param, in the order data is entered. */
export type OngletImports =
  'typologies' | 'emplacements' | 'stands' | 'animateurs' | 'grille-stands' | 'scenario';

export const ONGLETS_IMPORTS: readonly OngletImports[] = [
  'typologies',
  'emplacements',
  'stands',
  'animateurs',
  'grille-stands',
  // Last, and apart: a scenario is not a referential added to the edition but
  // the whole edition, replacing it.
  'scenario',
];

/** Reads the `onglet` query param; anything unknown is the typologies tab, where an edition starts. */
export function readOngletImports(value: string | null): OngletImports {
  return (ONGLETS_IMPORTS as readonly string[]).includes(value ?? '')
    ? (value as OngletImports)
    : 'typologies';
}
