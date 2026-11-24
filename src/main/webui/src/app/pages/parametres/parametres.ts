// The pure side of the Paramètres page: which of its tabs a query param names.

/** The tabs, and the values of the `onglet` query param. */
export type OngletParametres = 'edition' | 'mural' | 'instance' | 'historique' | 'mcp';

export const ONGLETS_PARAMETRES: readonly OngletParametres[] = [
  // The edition's own settings first: its name, its freeze, its guichets.
  'edition',
  // The links of the control room's television (ADR 0053): per edition.
  'mural',
  // Last, and apart: what the operator configured and what holds for the whole
  // database, every edition included — backup, simulated clock.
  'instance',
  // The journal of the actions and of the admin logins (the former
  // `/historique`, whose own « Connexions » tab is `?onglet=connexions`).
  'historique',
  // What an AI assistant needs to reach this instance (the former `/mcp-client`).
  'mcp',
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
  if (value === 'connexions') {
    return 'historique';
  }
  return (ONGLETS_PARAMETRES as readonly string[]).includes(value ?? '')
    ? (value as OngletParametres)
    : 'edition';
}
