package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * One vacation of a {@link GabaritDto}: its hours.
 */
public record VacationTypeDto(
        @NotNull LocalTime heureDebut, @NotNull LocalTime heureFin) {}
