package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A dated closure exception. {@code heureFin} is optional: absent means "until
 * closing time" — see {@code fr...domain.IndisponibiliteStand}.
 */
public record IndisponibiliteStandDto(
        @NotNull LocalDate date,
        @NotNull LocalTime heureDebut,
        LocalTime heureFin,
        String motif) {
}
