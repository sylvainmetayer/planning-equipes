package dev.sylvain.planning.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.SolverJobService;
import dev.sylvain.planning.service.SolverJobService.SolverBusyException;
import dev.sylvain.planning.service.SolverJobService.SolverJob;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Non-blocking counterpart of {@link PlanningResource}: submits a solve or an
 * analyze as a background job and lets the browser poll for the result instead
 * of holding an HTTP request open for minutes.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SolverJobResource {

    @Inject
    SolverJobService solverJobService;

    @Inject
    PlanningService planningService;

    @POST
    @Path("/solve/async")
    public Response solveAsync(PlanningFestival planningFestival, @QueryParam("seconds") Long secondsLimit) {
        try {
            SolverJob job = solverJobService.submitSolve(planningFestival, secondsLimit);
            return Response.accepted(JobView.withoutResult(job)).build();
        } catch (SolverBusyException e) {
            return busy(e);
        }
    }

    @POST
    @Path("/solve/analyze/async")
    public Response analyzeAsync(PlanningFestival planningFestival, @QueryParam("seconds") Long secondsLimit) {
        try {
            SolverJob job = solverJobService.submitAnalyze(planningFestival, secondsLimit);
            return Response.accepted(JobView.withoutResult(job)).build();
        } catch (SolverBusyException e) {
            return busy(e);
        }
    }

    /**
     * Launches a solve on a problem built entirely server-side from the
     * persisted reference data. The browser sends no planning at all, so even a
     * very large scenario (whose planning JSON would exceed the HTTP body limit)
     * can be solved.
     */
    @POST
    @Path("/solve/async/reference-data")
    @Consumes(MediaType.WILDCARD)
    public Response solveFromReferenceData(@QueryParam("seconds") Long secondsLimit) {
        try {
            SolverJob job = solverJobService.submitSolve(
                    planningService.construireDepuisReferenceData(), secondsLimit);
            return Response.accepted(JobView.withoutResult(job)).build();
        } catch (SolverBusyException e) {
            return busy(e);
        }
    }

    /** Server-side-built counterpart of {@link #analyzeAsync}. */
    @POST
    @Path("/solve/analyze/async/reference-data")
    @Consumes(MediaType.WILDCARD)
    public Response analyzeFromReferenceData(@QueryParam("seconds") Long secondsLimit) {
        try {
            SolverJob job = solverJobService.submitAnalyze(
                    planningService.construireDepuisReferenceData(), secondsLimit);
            return Response.accepted(JobView.withoutResult(job)).build();
        } catch (SolverBusyException e) {
            return busy(e);
        }
    }

    /**
     * The solver is a single shared resource: a second run is refused with the
     * job that currently holds it, so any client can display who is running.
     */
    private Response busy(SolverBusyException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(JobView.withoutResult(e.getActiveJob()))
                .build();
    }

    @GET
    @Path("/jobs")
    public List<JobView> listJobs() {
        return solverJobService.list().stream().map(JobView::withoutResult).toList();
    }

    /**
     * Server-side "is the solver busy?" flag, polled by every browser so a
     * running job locks the UI even in another session or a private window.
     * Returns 204 when the solver is idle.
     */
    @GET
    @Path("/jobs/active")
    public Response activeJob() {
        return solverJobService.findActive()
                .map(job -> Response.ok(JobView.withoutResult(job)).build())
                .orElseGet(() -> Response.noContent().build());
    }

    /**
     * Returns the job status, plus its payload once it is finished. The result
     * is only included for a completed job to keep polling responses small.
     */
    @GET
    @Path("/jobs/{id}")
    public Response getJob(@PathParam("id") String id) {
        return solverJobService.find(id)
                .map(job -> Response.ok(JobView.withResult(job)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/jobs/{id}")
    public Response deleteJob(@PathParam("id") String id) {
        try {
            return solverJobService.forget(id)
                    ? Response.noContent().build()
                    : Response.status(Response.Status.NOT_FOUND).build();
        } catch (SolverBusyException e) {
            return busy(e);
        }
    }

    /**
     * Stops a job started by mistake. A running solve/analyze is terminated
     * early (the Timefold solver returns its best solution so far, which is
     * still persisted/analyzed as usual) instead of being killed outright.
     */
    @POST
    @Path("/jobs/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    public Response cancelJob(@PathParam("id") String id) {
        return solverJobService.cancel(id)
                .map(job -> Response.ok(JobView.withoutResult(job)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    public record JobView(
            String id,
            String type,
            String status,
            String editionId,
            String editionNom,
            Long secondsLimit,
            Instant submittedAt,
            Instant startedAt,
            Instant finishedAt,
            long elapsedSeconds,
            String error,
            Object result) {

        public static JobView withoutResult(SolverJob job) {
            return build(job, null);
        }

        static JobView withResult(SolverJob job) {
            return build(job, job.getResult());
        }

        private static JobView build(SolverJob job, Object result) {
            return new JobView(
                    job.getId(),
                    job.getType().name(),
                    job.getStatus().name(),
                    job.getEditionId(),
                    job.getEditionNom(),
                    job.getSecondsLimit(),
                    job.getSubmittedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt(),
                    job.getElapsedSeconds(),
                    job.getError(),
                    result);
        }
    }
}
