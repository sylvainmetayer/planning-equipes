package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.NiveauEffort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * One stand. {@code id} is a reference local to the file — what the seats,
 * the ad hoc constraints and the consignes cite — and {@code code} the
 * readable key the import matches an existing stand on (ADR 0050).
 */
public record StandDto(
        @NotBlank String id,
        String code,
        @NotBlank String nom,
        String emplacementId,

        @NotEmpty(message = "un stand est toujours rattaché à au moins une typologie de jeu")
        List<String> typologiesProposees,

        /**
         * Boxed on purpose: a primitive would read an absent key as 0, and a
         * stand nobody sized would be staffed with one seat all festival long
         * without a word. The two are required, as the reader always did.
         */
        @NotNull @PositiveOrZero Integer effectifMin,
        @NotNull @PositiveOrZero Integer effectifMax,
        Boolean reserveMajeurs,
        Boolean premium,
        NiveauEffort niveauEffort,
        /** The stand's schedule: dated opening and closing windows. Absent or empty = open on every créneau. */
        List<@Valid PlageStandDto> plages,
        /**
         * The stand's model: recurring rules kept while the edition has no timeslot, and
         * unfolded into plages as it gains days (ADR 0104). A file that has timeslots carries
         * plages instead: the conversion of a 3.x file unfolds them.
         */
        List<@Valid HoraireStandDto> horaires) {}
