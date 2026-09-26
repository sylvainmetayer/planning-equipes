// The pure side of the Fichiers page: which of the three tabs a query param names.

/** The three tabs, and the values of the `onglet` query param. */
export type OngletFichiers = 'importer' | 'exporter' | 'archive';

export const ONGLETS_FICHIERS: readonly OngletFichiers[] = [
  // What fills an edition first: the page opens there.
  'importer',
  // Then what the edition writes of itself, which the Importer tab reads back.
  'exporter',
  // Last, once the event is over.
  'archive',
];

/** Reads the `onglet` query param; anything unknown is the Importer tab, the one the page opens on. */
export function readOngletFichiers(value: string | null): OngletFichiers {
  return (ONGLETS_FICHIERS as readonly string[]).includes(value ?? '')
    ? (value as OngletFichiers)
    : 'importer';
}
