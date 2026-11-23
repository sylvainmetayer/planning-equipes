// The tabs of the Animateurs page, read from `?onglet=`: pure, so the menu's
// spec checks the palette's « Animateurs › … » against the page's own reader.

/** The roster, the grid of competences, and what the animateurs declared. */
export type OngletAnimateurs = 'liste' | 'competences' | 'disponibilites';

/**
 * `?onglet=competences` or `?onglet=disponibilites`; `covoiturage`, the
 * Disponibilités screen's own second tab, opens that screen. Anything else is
 * the roster, the default.
 */
export function readOngletAnimateurs(value: string | null): OngletAnimateurs {
  if (value === 'competences') {
    return value;
  }
  return value === 'disponibilites' || value === 'covoiturage' ? 'disponibilites' : 'liste';
}
