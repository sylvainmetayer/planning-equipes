// Plural entity labels of the reference data, used by the bulk actions
// ("Supprimer 3 animateurs ?", "Modification de 3 animateurs effectuée.").
//
// Functions rather than constants on purpose: $localize must never run at
// module scope, only once main.ts has loaded the translations.

export function labelAnimateursPluriel(): string {
  return $localize`:@@animateurs.entityLabelPluriel:animateurs`;
}

export function labelStandsPluriel(): string {
  return $localize`:@@stands.entityLabelPluriel:stands`;
}

export function labelEmplacementsPluriel(): string {
  return $localize`:@@emplacements.entityLabelPluriel:emplacements`;
}

export function labelCreneauxPluriel(): string {
  return $localize`:@@creneaux.entityLabelPluriel:créneaux`;
}

export function labelTypologiesPluriel(): string {
  return $localize`:@@typologies.entityLabelPluriel:typologies`;
}

/** The manual adjustments — `ContrainteAdHoc` in the code, « ajustements » on screen. */
export function adjustmentsPluralLabel(): string {
  return $localize`:@@adHoc.entityLabelPlural:ajustements`;
}

export function locksPluralLabel(): string {
  return $localize`:@@verrouillages.entityLabelPlural:verrouillages`;
}
