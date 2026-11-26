package dev.sylvain.planning.scenario.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.sylvain.planning.scenario.ScenarioBinder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        // Absent means 1 and is converted on reading (ScenarioMigrations); a number above SCHEMA_VERSION is
        // refused. The bound follows the constant, so a new link of the chain only raises the constant.
        @Min(1) @Max(ScenarioBinder.SCHEMA_VERSION) Integer schemaVersion,
        @Valid EditionCibleDto edition,
        @NotNull @Valid FestivalDto festival,
        @Valid ParametresDto parametres,
        @Valid ContraintesDto contraintes,
        List<@Valid TypologieDto> typologies,
        @NotNull List<@Valid CreneauDto> creneaux,
        List<@Valid GabaritDto> gabarits,
        List<@Valid EmplacementDto> emplacements,
        @NotNull List<@Valid StandDto> stands,
        @NotNull List<@Valid AnimateurDto> animateurs,
        // Absent: ScenarioDomainMapper generates the postes itself from
        // stands x creneaux (mirroring buildFromReferenceData).
        List<@Valid PosteDto> postes,
        List<@Valid ContrainteAdHocDto> contraintesAdHoc,
        // The two consigne sections (ADR 0043) close the file: the presets, then
        // the dated consignes, whose openings and added créneaux name entries of
        // the sections above.
        List<@Valid PrereglageConsigneDto> prereglagesConsigne,
        List<@Valid ConsigneDto> consignes,
        // Closes the file: the seats and locks of a copy kept before a wipe, put back on what the sections above
        // wrote (ADR 0112). An ordinary export carries none.
        @Valid PlanDto plan) {

    /** The same scenario carrying {@code plan} as its last section. */
    public ScenarioDto withPlan(PlanDto plan) {
        return new ScenarioDto(
                schemaVersion,
                edition,
                festival,
                parametres,
                contraintes,
                typologies,
                creneaux,
                gabarits,
                emplacements,
                stands,
                animateurs,
                postes,
                contraintesAdHoc,
                prereglagesConsigne,
                consignes,
                plan);
    }

    // Shortcuts for the readers that want one section; the file only knows « parametres ».
    @JsonIgnore
    public ParametresLegauxDto parametresLegaux() {
        return parametres == null ? null : parametres.legal();
    }

    @JsonIgnore
    public ParametresQualiteDto parametresQualite() {
        return parametres == null ? null : parametres.organisation();
    }
}
