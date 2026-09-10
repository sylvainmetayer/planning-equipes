package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code animateurId} is accepted but ignored on import: PlanningService
 * builds each poste unassigned and leaves affectation to the solver.
 */
public record PosteDto(
        @NotBlank String id,
        @NotBlank String standId,
        @NotBlank String creneauId,
        String animateurId) {}
