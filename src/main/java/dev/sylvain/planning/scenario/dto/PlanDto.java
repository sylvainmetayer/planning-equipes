package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;
import java.util.List;

/**
 * The optional top-level {@code plan:} section: the seats filled and the locks
 * laid on the edition the file was exported from. Only the copy kept before a
 * wipe writes it; the import, which reads it like any other section, puts the
 * plan back on the referential it has just written.
 *
 * <p>Ids are those of the file, like every other section; a seat's timeslot
 * and a lock's are named by day and hours, the natural key, since a
 * timeslot's id means nothing in another edition (ADR 0084, ADR 0112).</p>
 */
public record PlanDto(List<@Valid AffectationPlanDto> affectations, List<@Valid VerrouPlanDto> verrous) {}
