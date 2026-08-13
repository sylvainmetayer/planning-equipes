package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Root of a scenario YAML file, as accepted by PlanningService's
 * import-scenario endpoints. Structural mirror only: fields that
 * PlanningService silently ignores on import (e.g. {@code creneaux[].jour},
 * {@code postes[].animateurId}) are still modeled here — so a well-formed
 * file validates cleanly — but are not given extra meaning.
 */
public record ScenarioDto(
        @NotNull @Valid FestivalDto festival,
        @NotNull List<@Valid CreneauDto> creneaux,
        List<@Valid EmplacementDto> emplacements,
        @NotNull List<@Valid StandDto> stands,
        @NotNull List<@Valid AnimateurDto> animateurs,
        @NotNull List<@Valid PosteDto> postes,
        @Valid ParametresLegauxDto parametresLegaux,
        @Valid ParametresDecoupageDto parametresDecoupage,
        @Valid ParametresSolveurDto parametresSolveur,
        @Valid DecoupageAutoDto decoupageAuto) {
}
