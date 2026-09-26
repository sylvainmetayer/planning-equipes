// The pure side of the Diagnostic page: which of the four tabs a query param names.

/**
 * The four tabs, and the values of the `onglet` query param: what blocks, what
 * is missing, where it is tight, what is fragile. Two former tabs live on
 * elsewhere, their addresses redirected by the routes: the bench (`banc`) is
 * « Qui peut tenir ce siège ? » in the Siège panel, and « À former »
 * (`former`) a section at the foot of Besoin. Tension is the former Marge
 * disponible screen's « après » and « tension » readings, merged.
 */
export type OngletDiagnostic = 'problemes' | 'besoin' | 'tension' | 'fragilite';

export const ONGLETS_DIAGNOSTIC: readonly OngletDiagnostic[] = [
  'problemes',
  'besoin',
  'tension',
  'fragilite',
];

/** Reads the `onglet` query param; anything unknown is the problems tab, the one the page opens on. */
export function readOnglet(value: string | null): OngletDiagnostic {
  return (ONGLETS_DIAGNOSTIC as readonly string[]).includes(value ?? '')
    ? (value as OngletDiagnostic)
    : 'problemes';
}
