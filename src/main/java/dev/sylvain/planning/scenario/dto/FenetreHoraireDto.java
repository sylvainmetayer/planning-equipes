package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * One window of a {@link HoraireStandDto}. {@code heureFin} is deliberately
 * optional: absent means "until closing time", the window then running to the
 * end of whatever créneau it is evaluated against — see
 * {@code fr...domain.FenetreHoraire}.
 */
public record FenetreHoraireDto(
        @NotNull LocalTime heureDebut,
        LocalTime heureFin) {
}
