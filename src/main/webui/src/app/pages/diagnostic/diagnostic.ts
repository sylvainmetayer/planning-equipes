// The pure side of the Diagnostic page: which of the five tabs a query param names.

/** The five tabs, and the values of the `onglet` query param. */
export type OngletDiagnostic = 'problemes' | 'besoin' | 'fragilite' | 'former' | 'banc';

export const ONGLETS_DIAGNOSTIC: readonly OngletDiagnostic[] = [
  'problemes',
  'besoin',
  'fragilite',
  'former',
  'banc',
];

/** Reads the `onglet` query param; anything unknown is the problems tab, the one the page opens on. */
export function readOnglet(value: string | null): OngletDiagnostic {
  return (ONGLETS_DIAGNOSTIC as readonly string[]).includes(value ?? '')
    ? (value as OngletDiagnostic)
    : 'problemes';
}
