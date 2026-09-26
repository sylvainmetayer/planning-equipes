// The pure side of Fichiers › Importer: which card the `cible` query param names.

/** The cards of the Importer tab, and the values of the `cible` query param, in the order data is entered. */
export type ImportCard =
  | 'typologies'
  | 'emplacements'
  | 'stands'
  | 'creneaux'
  | 'journees-types'
  | 'animateurs'
  | 'grille-stands'
  | 'scenario'
  | 'exemples'
  | 'verifier';

export const IMPORT_CARDS: readonly ImportCard[] = [
  'typologies',
  'emplacements',
  'stands',
  // The dates come before the animateurs: an off day only survives in an
  // edition that already has the matching timeslots.
  'creneaux',
  'journees-types',
  'animateurs',
  'grille-stands',
  // Apart: a scenario is not a referential added to the edition but the whole
  // edition, replacing it — from a file, or from the examples bundled with the
  // application; checking a file writes nothing at all.
  'scenario',
  'exemples',
  'verifier',
];

/** The cards a referential screen's « Importer » button opens in a dialog: one referential each. */
export type ReferentialImportCard = Extract<
  ImportCard,
  'typologies' | 'emplacements' | 'stands' | 'creneaux' | 'journees-types' | 'animateurs'
>;

/** Reads the `cible` query param; anything unknown is the typologies card, where an edition starts. */
export function readImportCard(value: string | null): ImportCard {
  return (IMPORT_CARDS as readonly string[]).includes(value ?? '')
    ? (value as ImportCard)
    : 'typologies';
}
