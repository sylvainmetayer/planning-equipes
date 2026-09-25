package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code parametresSolveur:} — the edition's solve budget. Either field may be
 * absent, and then the importing instance's default applies.
 */
public record ParametresSolveurDto(
        @Positive Integer dureeResolutionSecondes,
        @PositiveOrZero Integer plateauSecondes) {}
