package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One entry of the optional top-level {@code contraintesAdHoc:} scenario
 * section: a constraint entered by hand against this event's own data
 * (a forced unavailability, an incompatible pair, a forced assignment, an
 * affinity).
 *
 * <p>The ids are those of the file, not those of the database:
 * {@code creneauId} names an entry of the {@code creneaux:} section — a
 * scenario's créneaux get fresh database ids on import, and the import remaps
 * this reference along with them.</p>
 *
 * @param animateurs ids of the animateurs the constraint targets, in the order
 *                   the rule reads them (a pair for an incompatibility or an
 *                   affinity)
 * @param creneauId  id of the créneau the constraint is limited to, absent for
 *                   a constraint that covers the whole event
 * @param standId    id of the stand the constraint is limited to, absent for a
 *                   constraint that covers every stand
 * @param raison     free text the administrator wrote, kept as-is
 */
public record ContrainteAdHocDto(
        @NotBlank String id,
        @NotNull TypeContrainteAdHoc type,
        List<String> animateurs,
        String creneauId,
        String standId,
        String raison) {}
