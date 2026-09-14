// The pure side of the Imports page: which of the eight tabs a query param names.

/** The eight tabs, and the values of the `onglet` query param, in the order data is entered. */
export type OngletImports =
  | 'typologies'
  | 'emplacements'
  | 'stands'
  | 'creneaux'
  | 'journees-types'
  | 'animateurs'
  | 'grille-stands'
  | 'scenario';

export const ONGLETS_IMPORTS: readonly OngletImports[] = [
  'typologies',
  'emplacements',
  'stands',
  // The dates come before the animateurs: an off day only survives in an
  // edition that already has the matching timeslots.
  'creneaux',
  'journees-types',
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
