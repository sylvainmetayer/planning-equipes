package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalTime;

/**
 * Mirrors only the fields PlanningService#parseParametresLegaux actually
 * reads from a scenario file. {@code dureeHebdomadaireMaxMineurMinutes}
 * exists on the domain ParametresLegaux but is never read from scenario
 * YAML, so it is intentionally left out here to avoid implying it can be
 * overridden from a scenario file.
 *
 * <p>{@code heureDebutSoiree} is here for the opposite reason: leaving it out
 * did not mean « not overridable », it meant <b>silently reset</b> — importing
 * any scenario carrying a {@code parametresLegaux:} section wrote a fresh
 * object, so an edition set to 22:00 came back at 20:00 and every evening hour
 * of the equity table changed without a word.</p>
 *
 * <p>{@code dureeVacationMaxMinutes} arrived here when the découpage was
 * removed: it used to bound what the slicing produced, and now says from when
 * the grid check warns that a vacation needs an internal break.</p>
 */
public record ParametresLegauxDto(
        @Positive Integer dureeHebdomadaireMaxMinutes,
        @PositiveOrZero Integer pauseMinimaleEntreVacationsMinutes,
        @Positive Integer dureeVacationMaxMinutes,
        @PositiveOrZero Integer reposQuotidienMinimalMinutes,
        Boolean pauseSurPoste,
        @PositiveOrZero Integer coupureRepasMinutes,
        LocalTime coupureRepasMidiDebut,
        LocalTime coupureRepasMidiFin,
        LocalTime coupureRepasSoirDebut,
        LocalTime coupureRepasSoirFin,
        LocalTime heureDebutSoiree) {}
