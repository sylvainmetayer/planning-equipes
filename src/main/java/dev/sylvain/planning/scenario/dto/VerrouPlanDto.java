package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.TypeVerrouillage;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/** One lock of the {@code plan:} section; its timeslot, when it has one, is named by day and hours. */
public record VerrouPlanDto(
        @NotNull TypeVerrouillage type,
        String animateurId,
        String standId,
        LocalDate creneauDate,
        LocalTime creneauHeureDebut,
        LocalTime creneauHeureFin,
        LocalDate jour,
        String raison) {}
