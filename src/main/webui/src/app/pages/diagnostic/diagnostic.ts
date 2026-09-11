// The pure side of the Diagnostic page: which of the four tabs a query param names.

/** The four tabs, and the values of the `onglet` query param. */
export type OngletDiagnostic = 'problemes' | 'besoin' | 'fragilite' | 'banc';

export const ONGLETS_DIAGNOSTIC: readonly OngletDiagnostic[] = [
  'problemes',
  'besoin',
  'fragilite',
  'banc',
];

/** Reads the `onglet` query param; anything unknown is the problems tab, the one the page opens on. */
export function readOnglet(value: string | null): OngletDiagnostic {
  return (ONGLETS_DIAGNOSTIC as readonly string[]).includes(value ?? '')
    ? (value as OngletDiagnostic)
    : 'problemes';
}
