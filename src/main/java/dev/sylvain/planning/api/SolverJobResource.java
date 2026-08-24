package dev.sylvain.planning.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.sylvain.planning.config.ConfigJobStream;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.JobStreamBroadcaster;
import dev.sylvain.planning.service.ReplanificationScope;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.SolverJobService;
import dev.sylvain.planning.service.SolverJobService.SolverBusyException;
import dev.sylvain.planning.service.SolverJobService.SolverJob;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

/**
 * Non-blocking counterpart of {@link PlanningResource}: submits a solve or an
 * analyze as a background job and lets the browser poll for the result instead
 * of holding an HTTP request open for minutes.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SolverJobResource {

    /** Event name carrying a full {@link JobsState}. */
    static final String EVENT_STATE = "state";

    /** Event name of the keep-alive beat; carries no state. */
    static final String EVENT_HEARTBEAT = "heartbeat";

    @Inject
    SolverJobService solverJobService;

    @Inject
    PlanningService planningService;

    @Inject
    JobStreamBroadcaster jobStream;

    @Inject
    ConfigJobStream configJobStream;

    @Context
    Sse sse;

    @POST
    @Path("/solve/async")
    public Response solveAsync(PlanningEvenement planningEvenement, @QueryParam("seconds") Long secondsLimit) {
        SolverJob job = solverJobService.submitSolve(planningEvenement, secondsLimit);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    @POST
    @Path("/solve/analyze/async")
    public Response analyzeAsync(PlanningEvenement planningEvenement, @QueryParam("seconds") Long secondsLimit) {
        SolverJob job = solverJobService.submitAnalyze(planningEvenement, secondsLimit);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    /**
     * Launches a solve on a problem built entirely server-side from the
     * persisted reference data. The browser sends no planning at all, so even a
     * very large scenario (whose planning JSON would exceed the HTTP body limit)
     * can be solved.
     *
     * <p>{@code enFile=true} queues the solve instead of being refused when the
     * solver is busy: it starts by itself once the running job finishes, and
     * builds its problem at that moment — so the edition can keep being
     * prepared in the meantime.</p>
     */
    @POST
    @Path("/solve/async/reference-data")
    @Consumes(MediaType.WILDCARD)
    public Response solveFromReferenceData(@QueryParam("seconds") Long secondsLimit,
            @QueryParam("enFile") @DefaultValue("false") boolean enFile) {
        SolverJob job = solverJobService.submitSolveFromReferenceData(secondsLimit, enFile);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    /**
     * Incremental re-solve (issue #86): starts from the persisted plan, pins
     * whatever a late change did not invalidate, re-opens what the body names,
     * and re-fills only the rest — so a much shorter budget than a full solve
     * is enough (60 s by default). The problem is built inside the job itself,
     * from the persisted plan and today's reference data. Refused like any
     * other solve while one is running.
     *
     * <p>The body is optional: without it, the perimeter is exactly what the
     * late changes invalidated. {@code enFile=true} queues it behind the
     * running job instead of being refused.</p>
     */
    @POST
    @Path("/solve/incremental/async")
    public Response solveIncremental(ReplanificationScope scope, @QueryParam("seconds") Long secondsLimit,
            @QueryParam("enFile") @DefaultValue("false") boolean enFile) {
        SolverJob job = solverJobService.submitSolveIncremental(secondsLimit, scope, enFile);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    /** Server-side-built counterpart of {@link #analyzeAsync}. */
    @POST
    @Path("/solve/analyze/async/reference-data")
    @Consumes(MediaType.WILDCARD)
    public Response analyzeFromReferenceData(@QueryParam("seconds") Long secondsLimit) {
        SolverJob job = solverJobService.submitAnalyze(
                planningService.buildFromReferenceData(), secondsLimit);
        return Response.accepted(JobView.withoutResult(job)).build();
    }

    @GET
    @Path("/jobs")
    public List<JobView> listJobs() {
        return solverJobService.list().stream().map(JobView::withoutResult).toList();
    }

    /**
     * The jobs waiting for the solver, in the order they will run. Polled by
     * every client, like {@code /jobs/active}: the queue is server-side state,
     * shared by whoever is looking — a solve planned from another browser must
     * be visible (and removable) from this one.
     */
    @GET
    @Path("/jobs/file")
    public List<JobView> fileAttente() {
        return solverJobService.fileAttente().stream().map(JobView::withoutResult).toList();
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
     * Everything {@code /jobs/active} and {@code /jobs/file} say, pushed
     * instead of asked for, as server-sent events. One event carries both, so
     * following the solver costs one open connection rather than two requests
     * every couple of seconds — and a hand-over reaches the screen in the
     * second it happens rather than at the next tick.
     *
     * <p>Three things this stream must do, none of them optional:</p>
     * <ul>
     *   <li><b>Send the current state on connect</b>, not on the next
     *       transition. An idle solver produces no transition for hours, and a
     *       client that waited for one would show nothing at all.</li>
     *   <li><b>Beat regularly.</b> Silence on an idle solver looks exactly like
     *       a dead connection to a reverse proxy — Pangolin sits in front of
     *       this deployment — and to the browser. The heartbeat is a real named
     *       event, not only the {@code :keep-alive} comment it also carries: a
     *       comment keeps the bytes flowing but is invisible to
     *       {@code EventSource}, so it could not double as the liveness proof
     *       the client's fallback watches for.</li>
     *   <li><b>Never be the only source.</b> The browser keeps polling slowly
     *       and takes over when the stream goes quiet; a stream that dies
     *       silently must degrade into staleness of seconds, not of forever.
     *       See {@code SolverJobService} on the frontend.</li>
     * </ul>
     *
     * <p>Authentication needs nothing here: {@code /api/*} is
     * {@code authenticated} in {@code application.properties}, which the HTTP
     * layer applies before this method is reached, and {@code EventSource}
     * sends the session cookie on a same-origin stream like any other request.
     * An expired session therefore fails the stream open, which is exactly the
     * signal the client's fallback needs.</p>
     */
    @GET
    @Path("/jobs/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> streamJobs() {
        // merging, not concatenating: both upstreams are subscribed to at once,
        // so no transition can slip through between the initial state and the
        // subscription to the broadcaster. The item(0) is what makes the first
        // event the current state.
        Multi<OutboundSseEvent> states = Multi.createBy().merging()
                .streams(Multi.createFrom().item(0L), jobStream.changes())
                // The state is read and serialised off the publishing thread,
                // which holds the solver service monitor while it announces a
                // transition, and off the event loop.
                .emitOn(Infrastructure.getDefaultWorkerPool())
                // A client too slow to keep up gets the latest state, never a
                // backlog of stale ones: each event is a full snapshot.
                .onOverflow().dropPreviousItems()
                .map(version -> stateEvent());
        Multi<OutboundSseEvent> heartbeats = Multi.createFrom()
                .ticks().every(configJobStream.heartbeat())
                .onOverflow().drop()
                .map(tick -> heartbeatEvent());
        return Multi.createBy().merging().streams(states, heartbeats);
    }

    private OutboundSseEvent stateEvent() {
        JobsState state = new JobsState(
                solverJobService.findActive().map(JobView::withoutResult).orElse(null),
                solverJobService.fileAttente().stream().map(JobView::withoutResult).toList());
        return sse.newEventBuilder()
                .name(EVENT_STATE)
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(JobsState.class, state)
                .build();
    }

    private OutboundSseEvent heartbeatEvent() {
        return sse.newEventBuilder()
                .name(EVENT_HEARTBEAT)
                .comment("keep-alive")
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(Heartbeat.class, new Heartbeat(Instant.now()))
                .build();
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

    /**
     * One server-sent event's worth of solver state: what holds the solver, and
     * what waits behind it. The two used to be two requests; joining them is
     * what removes the second one.
     */
    public record JobsState(JobView active, List<JobView> file) {
    }

    /** Payload of a heartbeat: a timestamp, so the event is never empty. */
    public record Heartbeat(Instant at) {
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
