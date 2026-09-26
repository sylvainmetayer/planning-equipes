// The pure side of the Diagnostic page: which of the four tabs a query param names.

/**
 * The four tabs, and the values of the `onglet` query param. The bench was the
 * fifth: it is now « Qui peut tenir ce siège ? » in the Siège panel of the
 * Journée, and `onglet=banc` is redirected there by the routes.
 */
export type OngletDiagnostic = 'problemes' | 'besoin' | 'fragilite' | 'former';

export const ONGLETS_DIAGNOSTIC: readonly OngletDiagnostic[] = [
  'problemes',
  'besoin',
  'fragilite',
  'former',
];

/** Reads the `onglet` query param; anything unknown is the problems tab, the one the page opens on. */
export function readOnglet(value: string | null): OngletDiagnostic {
  return (ONGLETS_DIAGNOSTIC as readonly string[]).includes(value ?? '')
    ? (value as OngletDiagnostic)
    : 'problemes';
}
