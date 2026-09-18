package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A créneau a consigne added to the grid, named by its natural key — the
 * day and the hours — rather than by an id: the {@code creneaux:} section is
 * re-numbered on import, and this is the one key that survives the trip.
 * The créneau itself is listed under {@code creneaux:}; this entry only says
 * that the consigne, not the nominal grid, is what put it there.
 */
public record CreneauAjouteDto(
        @NotNull LocalDate date,
        @NotNull LocalTime heureDebut,
        @NotNull LocalTime heureFin) {}
