// The pure side of the espace planning page: which of the three tabs a query
// param names (issue #615).
//
// The espace is read DURING the event, standing, on a phone. One column of
// cards from the first day to the last answered « what am I doing this
// fortnight? » and nothing else: jumping to a day meant scrolling, and « am I
// with anybody I know? » had no answer at all. Three tabs, and the day first.

/** The three tabs, and the values of the `onglet` query param. */
export type OngletEspace = 'jour' | 'apercu' | 'coequipiers';

export const ONGLETS_ESPACE: readonly OngletEspace[] = ['jour', 'apercu', 'coequipiers'];

/** Reads the `onglet` query param; anything unknown is the day, the one the espace opens on. */
export function readOngletEspace(value: string | null): OngletEspace {
  return (ONGLETS_ESPACE as readonly string[]).includes(value ?? '')
    ? (value as OngletEspace)
    : 'jour';
}
