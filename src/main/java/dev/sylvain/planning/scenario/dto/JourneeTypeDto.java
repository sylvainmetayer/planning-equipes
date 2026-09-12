package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/**
 * One entry of the optional top-level {@code journeesTypes:} section: a named
 * day template — its vacations — and the dates it governs (ADR 0032).
 *
 * <p>The section is applied <b>after</b> the créneaux landed and replaces the
 * edition's templates and calendar wholesale. It does not write créneaux: a
 * file consistent with itself lists under {@code creneaux:} exactly what its
 * templates say, and applying the calendar right after the import changes
 * nothing. When the section is absent, the import recognises the templates
 * the créneaux imply, so that an imported edition and a hand-entered one look
 * the same on the Créneaux screen.</p>
 */
public record JourneeTypeDto(
        @NotBlank String nom, @NotEmpty List<@Valid VacationTypeDto> vacations, List<LocalDate> dates) {}
