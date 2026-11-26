package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;

/**
 * The {@code parametres:} section of a scenario, in the sections the Paramètres
 * screen has: {@code legal} and {@code organisation} (the quality thresholds).
 * Every one is optional; the notifications and the solve budget (the
 * instance's, ADR 0110) are not part of a scenario file.
 */
public record ParametresDto(
        @Valid ParametresLegauxDto legal, @Valid ParametresQualiteDto organisation) {}
