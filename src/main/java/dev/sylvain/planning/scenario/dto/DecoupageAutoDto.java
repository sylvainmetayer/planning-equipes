package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Structural mirror only: {@code groupeSourceNom != groupeCibleNom} is
 * enforced by {@code DecoupageAutoConfig}'s compact constructor at import
 * time, not re-checked here.
 */
public record DecoupageAutoDto(
        @NotBlank String groupeSourceNom,
        @NotBlank String groupeCibleNom) {
}
