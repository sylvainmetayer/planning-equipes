package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * One vacation of a {@link JourneeTypeDto}: the hours, and {@code couverturePause}
 * for a meal relay — same meaning as on a créneau, absent means false.
 */
public record VacationTypeDto(
        @NotNull LocalTime heureDebut, @NotNull LocalTime heureFin, Boolean couverturePause) {}
