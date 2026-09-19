// The pure side of the Paramètres page: which of the four tabs a query param names.

/** The four tabs, and the values of the `onglet` query param. */
export type OngletParametres = 'legaux' | 'edition' | 'emails' | 'globaux';

export const ONGLETS_PARAMETRES: readonly OngletParametres[] = [
  // The legal floor first: it is what an edition is checked against, and the
  // only tab the incoming links of Pauses and Équité aim at without naming it.
  'legaux',
  'edition',
  'emails',
  // Last, and apart: the whole database, every edition included.
  'globaux',
];

/** Reads the `onglet` query param; anything unknown is the legal tab, the one the page opens on. */
export function readOngletParametres(value: string | null): OngletParametres {
  return (ONGLETS_PARAMETRES as readonly string[]).includes(value ?? '')
    ? (value as OngletParametres)
    : 'legaux';
}
