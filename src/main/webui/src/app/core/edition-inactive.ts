// The refusal of an outward action from an edition that is not the active one
// (ADR 0072), as the client words it. Kept out of any component: the buttons'
// note and the interceptor's safety net say the same sentence.

/** The `code` of the 409 the server answers an outward call from an inactive edition with. */
export const CODE_EDITION_INACTIVE = 'EDITION_INACTIVE';

/** Called lazily (never at module scope): `$localize` needs the catalogue `main.ts` loads. */
export function editionInactivePhrase(): string {
  return $localize`:@@edition.inactive.phrase:Cette édition n'est pas active : seule l'édition active publie et écrit aux animateurs. Activez-la sur la page Éditions.`;
}
