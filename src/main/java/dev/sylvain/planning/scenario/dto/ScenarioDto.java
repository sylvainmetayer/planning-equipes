package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Root of a scenario YAML file: the one shape a scenario has, whether it is
 * being validated, imported or written back out.
 *
 * <p>Fields the import silently ignores (e.g. {@code creneaux[].jour},
 * {@code postes[].animateurId}) are still modeled, so a well-formed file
 * validates cleanly — but they are not given extra meaning.</p>
 *
 * <p><b>The order of the components is the order of the file.</b> Since the
 * export serialises this record, declaring a field here is what puts it in the
 * written scenario, at that place. It is set to the order the hand-written
 * writer emitted before A2, so that switching to this one moved no line of any
 * exported file.</p>
 */
public record ScenarioDto(
        @Valid EditionCibleDto edition,
        @NotNull @Valid FestivalDto festival,
        @Valid ParametresSolveurDto parametresSolveur,
        @Valid ParametresLegauxDto parametresLegaux,
        @Valid ParametresQualiteDto parametresQualite,
        @Valid ContraintesDto contraintes,
        List<@Valid TypologieDto> typologies,
        @NotNull List<@Valid CreneauDto> creneaux,
        List<@Valid JourneeTypeDto> journeesTypes,
        List<@Valid EmplacementDto> emplacements,
        @NotNull List<@Valid StandDto> stands,
        @NotNull List<@Valid AnimateurDto> animateurs,
        // Absent: ScenarioDomainMapper generates the postes itself from
        // stands x creneaux (mirroring buildFromReferenceData).
        List<@Valid PosteDto> postes,
        List<@Valid ContrainteAdHocDto> contraintesAdHoc) {}
