package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.TypologieJeu;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record StandDto(
        @NotBlank String id,
        @NotBlank String nom,
        @NotNull List<TypologieJeu> typologiesProposees,
        @PositiveOrZero int effectifMin,
        @PositiveOrZero int effectifMax,
        Boolean reserveMajeurs,
        Boolean premium,
        String emplacementId,
        List<@Valid IndisponibiliteStandDto> indisponibilites) {
}
