// The pure side of the Diagnostic page: which of the six tabs a query param names.

/**
 * The six tabs, and the values of the `onglet` query param: what blocks, what
 * is missing, what is at risk, what each person carries, how the breaks and
 * meals sit, whether it is legal.
 * Former tabs live on
 * elsewhere, their addresses redirected by the routes: the bench (`banc`) is
 * « Qui peut tenir ce siège ? » in the Siège panel, and `fragilite`,
 * `former` and `tension` are the three sections of Risques.
 */
export type OngletDiagnostic =
  'problemes' | 'besoin' | 'risques' | 'charge' | 'repas-pauses' | 'conformite';

export const ONGLETS_DIAGNOSTIC: readonly OngletDiagnostic[] = [
  'problemes',
  'besoin',
  'risques',
  'charge',
  'repas-pauses',
  'conformite',
];

/** The sections of the Risques tab, in reading order: `section=` scrolls to one. */
export type SectionRisques = 'fragilite' | 'former' | 'tension';

export const SECTIONS_RISQUES: readonly SectionRisques[] = ['fragilite', 'former', 'tension'];

/** Reads the `onglet` query param; anything unknown is the problems tab, the one the page opens on. */
export function readOnglet(value: string | null): OngletDiagnostic {
  return (ONGLETS_DIAGNOSTIC as readonly string[]).includes(value ?? '')
    ? (value as OngletDiagnostic)
    : 'problemes';
}
