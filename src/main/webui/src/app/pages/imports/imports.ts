// The pure side of Fichiers › Importer: which card the `cible` query param names.

/**
 * The cards of the Importer tab, and the values of the `cible` query param.
 * The YAML scenario is the workbook of a whole edition; the animateurs' CSV is
 * the one table file left (the others, and the stand matrix, were withdrawn in
 * 4.0: a bookmark naming them lands on the scenario card).
 */
export type ImportCard = 'scenario' | 'animateurs' | 'exemples' | 'verifier';

export const IMPORT_CARDS: readonly ImportCard[] = [
  'scenario',
  'animateurs',
  'exemples',
  'verifier',
];

/** The cards a referential screen's « Importer » button opens in a dialog: one referential each. */
export type ReferentialImportCard = Extract<ImportCard, 'animateurs'>;

/** Reads the `cible` query param; anything unknown — a withdrawn card included — is the scenario card. */
export function readImportCard(value: string | null): ImportCard {
  return (IMPORT_CARDS as readonly string[]).includes(value ?? '')
    ? (value as ImportCard)
    : 'scenario';
}
