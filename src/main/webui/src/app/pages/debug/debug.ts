// The pure side of the Débogage page: which of the two tabs a query param names.

/** The two tabs, and the values of the `onglet` query param. */
export type OngletDebug = 'resolution' | 'verifications';

export const ONGLETS_DEBUG: readonly OngletDebug[] = [
  // What a solve left behind, which is what this page is opened for.
  'resolution',
  'verifications',
];

/** Reads the `onglet` query param; anything unknown is the resolution tab, the one the page opens on. */
export function readOngletDebug(value: string | null): OngletDebug {
  return (ONGLETS_DEBUG as readonly string[]).includes(value ?? '')
    ? (value as OngletDebug)
    : 'resolution';
}
