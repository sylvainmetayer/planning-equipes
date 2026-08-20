package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Optional top-level {@code edition:} scenario section: designates the edition
 * the import writes into, instead of the caller's current one. A missing
 * edition is created empty first (with {@code nom} as its display name,
 * defaulting to the id); an existing one is reused as-is. The import response
 * reports which of the two happened, so the UI can show the mandatory recap.
 */
public record EditionCibleDto(@NotBlank String id, String nom) {
}
