package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalTime;

/**
 * Optional top-level {@code parametresQualite:} scenario section: the
 * organisational-quality thresholds the file was verified with.
 *
 * <p>Persisted per edition since issue #591. A file exported from an edition
 * that raised its typology ceiling and re-imported elsewhere would otherwise
 * solve a different problem, silently — the same hole
 * {@code parametresLegaux}, {@code parametresSolveur} and {@code contraintes}
 * were added to close.</p>
 *
 * <p>The two hours are <b>wholesale</b>: present, the section describes the
 * whole quality tuning, so an hour it leaves out is an hour the edition does
 * not have — which is exactly how {@code eviterFermeturePuisOuverture} is
 * neutralised. An export always writes every field, so a round trip is
 * exact. The numbers keep the usual convention instead — what the file does
 * not carry keeps the importing edition's value — because no number of theirs
 * can mean « none ».</p>
 */
public record ParametresQualiteDto(
        @Positive Integer maxEmplacementsDistinctsParJour,
        LocalTime heureServiceTardif,
        LocalTime heureServiceMatinal,
        @PositiveOrZero Integer reposSouhaiteApresServiceTardifMinutes,
        @Positive Integer typologiesDistinctesMax,
        @Positive Integer joursConsecutifsMax,
        @Positive @DecimalMax("15") Double vitesseMarcheKmH,
        @DecimalMin("1") @DecimalMax("5") Double facteurDetour,
        @PositiveOrZero @Max(120) Integer toleranceTrajetMinutes) {}
