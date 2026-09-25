package dev.sylvain.planning.service.solve;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the operator decided for every edition of this instance, read-only from
 * the application: the budget an edition that set nothing runs under, and the
 * ceilings an edition may not exceed. Environment variables, like
 * {@code BACKUP_RETENTION}: the solver is shared by every edition, one job at a
 * time, so how long one of them may hold it is not an edition's decision.
 *
 * @param defaultSecondsLimit  {@code planning.solver.seconds-limit}
 * @param defaultPlateauSeconds {@code planning.solver.unimproved-seconds-limit}, {@code 0} = never
 * @param maxSecondsLimit      {@code planning.solver.seconds-limit-max} ({@code SOLVER_SECONDS_LIMIT_MAX})
 * @param maxPlateauSeconds    {@code planning.solver.unimproved-seconds-limit-max}
 *                             ({@code SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX})
 */
@Schema(requiredProperties = {"defaultSecondsLimit", "defaultPlateauSeconds", "maxSecondsLimit", "maxPlateauSeconds"})
public record SolverBudgetBounds(
        long defaultSecondsLimit, long defaultPlateauSeconds, long maxSecondsLimit, long maxPlateauSeconds) {}
