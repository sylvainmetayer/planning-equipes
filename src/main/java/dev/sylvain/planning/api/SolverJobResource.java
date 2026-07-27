package dev.sylvain.planning.api;

import java.time.Instant;
import java.util.List;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.SolverJobService;
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
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SolverJobResource {

    @Inject
    SolverJobService solverJobService;

    @POST
    @Path("/solve/async")
    public Response solveAsync(PlanningFestival planningFestival, @QueryParam("seconds") Long secondsLimit) {
        SolverJob job = solverJobService.submitSolve(planningFestival, secondsLimit);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    @POST
    @Path("/solve/analyze/async")
    public Response analyzeAsync(PlanningFestival planningFestival, @QueryParam("seconds") Long secondsLimit) {
        SolverJob job = solverJobService.submitAnalyze(planningFestival, secondsLimit);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    @GET
    @Path("/jobs")
    public List<JobView> listJobs() {
        return solverJobService.list().stream().map(JobView::withoutResult).toList();
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
        return solverJobService.forget(id)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    public record JobView(
            String id,
            String type,
            String status,
            Long secondsLimit,
            Instant submittedAt,
            Instant startedAt,
            Instant finishedAt,
            String error,
            Object result) {

        static JobView withoutResult(SolverJob job) {
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
                    job.getSecondsLimit(),
                    job.getSubmittedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt(),
                    job.getError(),
                    result);
        }
    }
}
