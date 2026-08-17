package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A dated opening exception. {@code heureFin} is optional: absent means "until
 * closing time" — see {@code fr...domain.OuvertureStand}.
 */
public record OuvertureStandDto(
        @NotNull LocalDate date,
        @NotNull LocalTime heureDebut,
        LocalTime heureFin,
        String motif) {
}
