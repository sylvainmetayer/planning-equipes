package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalTime;

/**
 * One window of a {@link HoraireStandDto}. {@code heureFin} is deliberately
 * optional: absent means "until closing time", the window then running to the
 * end of whatever créneau it is evaluated against — see
 * {@code fr...domain.FenetreHoraire}.
 *
 * <p>{@code effectif} is optional too: absent means "inherit the stand's
 * {@code effectifMin}". It is how a stand whose staffing varies during the day
 * is expressed as one stand rather than several.</p>
 */
public record FenetreHoraireDto(
        @NotNull LocalTime heureDebut,
        LocalTime heureFin,
        @Positive Integer effectif) {
}
