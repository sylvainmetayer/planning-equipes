package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * {@code jour} is accepted but ignored on import: PlanningService recomputes
 * it from {@code date} via Creneau.assignerJours, so a mismatched value in
 * the file has no effect.
 *
 * <p>{@code couverturePause} marks a shift that covers a meal service: the
 * stand stays open on it but at half its usual headcount, exactly as the
 * découpage flags the coverage vacations it generates. Absent means false —
 * a normal shift.</p>
 */
public record CreneauDto(
        @NotBlank String id,
        Integer jour,
        @NotNull LocalDate date,
        @NotNull LocalTime heureDebut,
        @NotNull LocalTime heureFin,
        Boolean couverturePause) {}
