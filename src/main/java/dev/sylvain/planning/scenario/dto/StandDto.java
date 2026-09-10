package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.NiveauEffort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record StandDto(
        @NotBlank String id,
        @NotBlank String nom,
        String emplacementId,

        @NotEmpty(message = "un stand est toujours rattaché à au moins une typologie de jeu")
        List<String> typologiesProposees,

        @PositiveOrZero int effectifMin,
        @PositiveOrZero int effectifMax,
        Boolean reserveMajeurs,
        Boolean premium,
        NiveauEffort niveauEffort,
        /**
         * Relay family on a staggered grid, 0 = the first one (issue #390).
         * Absent: the import lets the least populated one be picked, which is
         * what a file written by hand wants; an export carries the value so a
         * scenario re-imported keeps the pairing it was solved with.
         */
        Integer famille,
        List<@Valid IndisponibiliteStandDto> indisponibilites,
        List<@Valid OuvertureStandDto> ouvertures,
        /**
         * Recurring opening/closing rules — what a stable pattern is written as,
         * instead of one dated entry per event day in the two lists above,
         * which stay for the per-date exceptions that override them.
         */
        List<@Valid HoraireStandDto> horaires) {}
