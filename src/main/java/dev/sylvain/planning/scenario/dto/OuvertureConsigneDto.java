package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalTime;

/**
 * One stand a consigne opens on one window — an extension of its own hours,
 * outside the band. {@code standId} names an entry of the {@code stands:}
 * section of the same file. {@code fin} absent reads « jusqu'à minuit »;
 * {@code effectif} absent inherits the highest headcount the stand loses to
 * the band, or its minimum when it loses nothing.
 */
public record OuvertureConsigneDto(
        @NotBlank String standId,
        @NotNull LocalTime debut,
        LocalTime fin,
        @Positive Integer effectif) {}
