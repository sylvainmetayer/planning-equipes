package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One emplacement. {@code id} is a reference local to the file, what the
 * stands' {@code emplacementId} cite; {@code code} is the readable key the
 * import matches an existing emplacement on (ADR 0050).
 */
public record EmplacementDto(
        @NotBlank String id, String code, @NotBlank String nom, Double latitude, Double longitude) {}
