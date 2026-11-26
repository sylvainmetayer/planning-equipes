package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.ModeHoraire;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A dated window of a stand's schedule: on {@code date}, between
 * {@code heureDebut} and {@code heureFin}, the stand is open or closed
 * ({@code mode}). {@code heureFin} is optional: absent means "until closing
 * time"; {@code effectif} is optional too, absent meaning the stand's own
 * minimum headcount, and only an opening carries one — see
 * {@code dev.sylvain.planning.domain.PlageStand}. {@code depliee} says the application unfolded
 * the plage from the stand's model (its normal regime); absent, somebody typed it.
 */
public record PlageStandDto(
        @NotNull LocalDate date,
        @NotNull ModeHoraire mode,
        @NotNull LocalTime heureDebut,
        LocalTime heureFin,
        String motif,
        @Positive Integer effectif,
        Boolean depliee) {}
