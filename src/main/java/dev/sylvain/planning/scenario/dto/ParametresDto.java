package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;

/**
 * The {@code parametres:} section of a scenario, in the sections the Paramètres
 * screen has: {@code legal}, {@code organisation} (the quality thresholds) and
 * {@code instance} (the solve budget). Every one is optional; the
 * notifications are not part of a scenario file.
 */
public record ParametresDto(
        @Valid ParametresLegauxDto legal,
        @Valid ParametresQualiteDto organisation,
        @Valid ParametresSolveurDto instance) {}
