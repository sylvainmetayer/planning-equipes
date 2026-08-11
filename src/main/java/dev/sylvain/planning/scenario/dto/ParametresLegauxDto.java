package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Mirrors only the fields PlanningService#parseParametresLegaux actually
 * reads from a scenario file. {@code dureeHebdomadaireMaxMineurMinutes}
 * exists on the domain ParametresLegaux but is never read from scenario
 * YAML, so it is intentionally left out here to avoid implying it can be
 * overridden from a scenario file.
 */
public record ParametresLegauxDto(
        @Positive Integer dureeHebdomadaireMaxMinutes,
        @PositiveOrZero Integer pauseMinimaleEntreVacationsMinutes,
        @PositiveOrZero Integer reposQuotidienMinimalMinutes) {
}
