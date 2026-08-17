package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * A recurring opening/closing rule of a stand — the scenario-file form of
 * {@code fr...domain.HoraireStand}.
 *
 * <p>The day selector is <b>flattened onto the rule</b> rather than nested in a
 * sub-object: {@code jours} names which of the sibling fields applies, and only
 * that one is read. It keeps the frequent case down to two lines
 * ({@code mode} + {@code fenetres}, {@code jours} defaulting to
 * {@link TypeJoursHoraire#TOUS}) and spares both the YAML reader and the
 * generated JSON schema any polymorphism.</p>
 *
 * <p>Which sibling a selector requires ({@code joursSemaine} for
 * {@code JOURS_SEMAINE}, {@code dateDebut}/{@code dateFin} for {@code PLAGE},
 * {@code dates} for {@code DATES}) can't be expressed as a Bean Validation
 * annotation, so it is checked in {@code ReferenceDataService#validateHoraires}
 * — the same place that rejects two rules of equal scope disagreeing on the
 * mode.</p>
 */
public record HoraireStandDto(
        @NotNull ModeHoraire mode,
        TypeJoursHoraire jours,
        List<DayOfWeek> joursSemaine,
        LocalDate dateDebut,
        LocalDate dateFin,
        List<LocalDate> dates,
        @NotEmpty List<@Valid FenetreHoraireDto> fenetres,
        String motif) {
}
