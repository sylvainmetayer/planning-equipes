// The pure side of the Paramètres page: which of the three tabs a query param names.

/** The three tabs, and the values of the `onglet` query param. */
export type OngletParametres = 'edition' | 'mural' | 'instance';

export const ONGLETS_PARAMETRES: readonly OngletParametres[] = [
  // The edition's own settings first: its name, its freeze, its guichets.
  'edition',
  // The links of the control room's television (ADR 0053): per edition.
  'mural',
  // Last, and apart: what the operator configured and what holds for the whole
  // database, every edition included — backup, SQL dump, simulated clock.
  'instance',
];

/**
 * Reads the `onglet` query param. `globaux` was the instance tab's former
 * name: a bookmark on it lands on `instance`. `legaux` and `emails` left the
 * page and are redirected before it opens (`parametresOngletsDeplaces`);
 * anything else unknown is the edition tab, the one the page opens on.
 */
export function readOngletParametres(value: string | null): OngletParametres {
  if (value === 'globaux') {
    return 'instance';
  }
  return (ONGLETS_PARAMETRES as readonly string[]).includes(value ?? '')
    ? (value as OngletParametres)
    : 'edition';
}
