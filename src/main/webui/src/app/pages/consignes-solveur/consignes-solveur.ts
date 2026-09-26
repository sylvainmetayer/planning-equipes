/** The three tabs of « Consignes au solveur », and the values of `?onglet=`. */
export type OngletConsignesSolveur = 'ajustements' | 'verrouillages' | 'consignes';

export const ONGLETS_CONSIGNES_SOLVEUR: readonly OngletConsignesSolveur[] = [
  'ajustements',
  'verrouillages',
  'consignes',
];

/**
 * `?onglet=`, falling back to « Ajustements » on anything else: a hand-edited
 * value or a link from an older version opens the first tab rather than an
 * empty page.
 */
export function readOngletConsignesSolveur(value: string | null): OngletConsignesSolveur {
  return ONGLETS_CONSIGNES_SOLVEUR.find((onglet) => onglet === value) ?? 'ajustements';
}
