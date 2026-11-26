// The pure side of the Paramètres page: which of its tabs a query param names.

/** The tabs, and the values of the `onglet` query param. */
export type OngletParametres =
  | 'edition'
  | 'notifications'
  | 'legal'
  | 'organisation'
  | 'calcul'
  | 'mural'
  | 'comptes'
  | 'instance'
  | 'historique'
  | 'mcp';

export const ONGLETS_PARAMETRES: readonly OngletParametres[] = [
  // The edition's own settings first: its name, its freeze, its guichets.
  'edition',
  // The four sections of the edition's parameters (one per `/api/parametres/{section}`):
  // the e-mails it sends of itself, the legal ceilings, the comfort thresholds, the solve.
  'notifications',
  'legal',
  'organisation',
  'calcul',
  // The links of the control room's television (ADR 0053): per edition.
  'mural',
  // Who may sign in and with which rights: the administrators' alone.
  'comptes',
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
 * The sections an expert tunes: their tabs show under « Réglages avancés », or
 * when the address names one. The notifications are an everyday setting.
 */
export const ONGLETS_AVANCES: readonly OngletParametres[] = ['legal', 'organisation', 'calcul'];

/** The tabs only an administrator is offered: the server refuses their routes to anybody else. */
export const ONGLETS_ADMIN: readonly OngletParametres[] = ['comptes', 'instance', 'mcp'];

/**
 * Reads the `onglet` query param. `globaux` was the instance tab's former
 * name: a bookmark on it lands on `instance`. `legaux` and `emails` are redirected
 * before the page opens (`parametresOngletsDeplaces`) to the section that holds them;
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
