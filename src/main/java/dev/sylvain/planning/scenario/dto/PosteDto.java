package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code animateurId} is accepted but ignored on import: PlanningService
 * builds each poste unassigned and leaves affectation to the solver.
 *
 * <p>{@code optionnel} is a renfort (issue #505): a seat generated above the
 * staffing the window declares, which nobody is owed. Boxed and written only
 * when true, for the reason a window's effectif is written only when it was
 * chosen — and read back, because a file that lost the flag would turn every
 * renfort it carries into a seat the solver must fill, which is exactly the
 * inflation {@code effectifMax} is kept out of.</p>
 */
public record PosteDto(
        @NotBlank String id,
        @NotBlank String standId,
        @NotBlank String creneauId,
        String animateurId,
        Boolean optionnel) {}
