package dev.sylvain.planning.service.solve;

/**
 * A solve has just taken the solver, fired synchronously on its thread before
 * it builds anything.
 *
 * <p>The solver is shared by every edition, one job at a time; the only other
 * thing that may hold the cores is a staffing check, which gives way on this
 * event ({@link StaffingVerificationService}) rather than slow down a solve
 * whose result is budgeted on time.</p>
 *
 * @param jobId the job that starts
 */
public record SolveStarting(String jobId) {}
