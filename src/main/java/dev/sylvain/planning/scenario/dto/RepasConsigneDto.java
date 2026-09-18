package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.LocalTime;

/**
 * The meal windows a consigne or a preset states instead of the edition's
 * (ADR 0043, V92): each field absent keeps the edition's value, and the
 * {@code justification} is required as soon as the block is written — it is
 * printed beside the day, and a departure from the organiser's rule without
 * one is what the block exists to prevent.
 */
public record RepasConsigneDto(
        LocalTime midiDebut,
        LocalTime midiFin,
        LocalTime soirDebut,
        LocalTime soirFin,
        @Positive Integer coupureMinutes,
        @NotBlank String justification) {}
