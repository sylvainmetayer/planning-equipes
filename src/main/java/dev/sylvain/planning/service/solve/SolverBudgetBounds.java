package dev.sylvain.planning.service.solve;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The solve budget of this instance, read-only from the application: what
 * every solve runs under, and the ceiling a duration asked for at launch may
 * not exceed. Environment variables, like {@code BACKUP_RETENTION}: the solver
 * is shared by every edition, one job at a time, so how long one of them may
 * hold it is not an edition's decision (ADR 0110).
 *
 * @param defaultSecondsLimit   {@code planning.solver.seconds-limit}: how long a solve runs at most
 * @param defaultPlateauSeconds {@code planning.solver.unimproved-seconds-limit}: how long an already
 *                              feasible plan may go without improving, {@code 0} = never
 * @param maxSecondsLimit       {@code planning.solver.seconds-limit-max} ({@code SOLVER_SECONDS_LIMIT_MAX}):
 *                              the most a launch may ask for
 */
@Schema(requiredProperties = {"defaultSecondsLimit", "defaultPlateauSeconds", "maxSecondsLimit"})
public record SolverBudgetBounds(long defaultSecondsLimit, long defaultPlateauSeconds, long maxSecondsLimit) {}
