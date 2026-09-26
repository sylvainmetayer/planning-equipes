// The pure side of the Paramètres page: which of the five tabs a query param names.

/** The five tabs, and the values of the `onglet` query param. */
export type OngletParametres = 'legaux' | 'edition' | 'emails' | 'mural' | 'instance';

export const ONGLETS_PARAMETRES: readonly OngletParametres[] = [
  // The legal floor first: it is what an edition is checked against, and the
  // only tab the incoming links of Pauses and Équité aim at without naming it.
  'legaux',
  'edition',
  'emails',
  // The links of the control room's television (ADR 0053): per edition.
  'mural',
  // Last, and apart: what the operator configured and what holds for the whole
  // database, every edition included — backup, SQL dump, simulated clock.
  'instance',
];

/** The names a tab was known by before, still read from old links and bookmarks. */
const FORMER_NAMES: Readonly<Record<string, OngletParametres>> = { globaux: 'instance' };

/** Reads the `onglet` query param; anything unknown is the legal tab, the one the page opens on. */
export function readOngletParametres(value: string | null): OngletParametres {
  const nom = FORMER_NAMES[value ?? ''] ?? value ?? '';
  return (ONGLETS_PARAMETRES as readonly string[]).includes(nom)
    ? (nom as OngletParametres)
    : 'legaux';
}
