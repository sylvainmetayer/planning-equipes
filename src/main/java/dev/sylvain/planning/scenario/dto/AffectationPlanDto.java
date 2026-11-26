package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One seat of the {@code plan:} section.
 *
 * @param animateurId        absent for an empty seat
 * @param heureDebutEffective the hours the animateur really works when they differ from the timeslot's, absent
 *                           otherwise
 * @param suiteDe            the seat this one is the remainder of, when it was split on the day
 */
public record AffectationPlanDto(
        @NotBlank String posteId,
        @NotBlank String standId,
        @NotNull LocalDate date,
        @NotNull LocalTime heureDebut,
        @NotNull LocalTime heureFin,
        String animateurId,
        LocalTime heureDebutEffective,
        LocalTime heureFinEffective,
        String suiteDe) {}
