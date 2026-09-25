package dev.sylvain.planning.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.config.ConfigJobStream;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.JobStreamBroadcaster;
import dev.sylvain.planning.service.solve.Reamorcage;
import dev.sylvain.planning.service.solve.ReplanificationScope;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import dev.sylvain.planning.service.solve.SolverScoreTrace;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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

    /** Event name carrying new points of the running solve's score curve. */
    static final String EVENT_SCORE = "score";

    /**
     * Generation of "no curve at all": what a cursor holds before its first
     * event, and what {@link #absenceEvent} sends. A real generation starts at
     * 1, so the two can never be confused.
     */
    private static final int GENERATION_ABSENTE = -1;

    private final SolverJobService solverJobService;

    private final JobStreamBroadcaster jobStream;

    private final ConfigJobStream configJobStream;

    private final SolverScoreTrace scoreTrace;

    private final EditionContext editionContext;

    @Inject
    public SolverJobResource(
            SolverJobService solverJobService,
            JobStreamBroadcaster jobStream,
            ConfigJobStream configJobStream,
            SolverScoreTrace scoreTrace,
            EditionContext editionContext) {
        this.solverJobService = solverJobService;
        this.jobStream = jobStream;
        this.configJobStream = configJobStream;
        this.scoreTrace = scoreTrace;
        this.editionContext = editionContext;
    }

    @Context
    Sse sse;

    @POST
    @Path("/solve/async")
    public Response solveAsync(PlanningEvenement planningEvenement, @QueryParam("seconds") Long secondsLimit) {
        SolverJob job = solverJobService.submitSolve(planningEvenement, secondsLimit);
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
     *
     * <p>{@code reamorcage} says where to start from (issue #174): {@code AUTO}
     * (the default) re-seeds from the persisted plan when there is one,
     * {@code PLAN_COURANT} insists on it, {@code AUCUN} starts cold. Cold is
     * the case that loses the plan already reached, hence not the default —
     * for a script as much as for the screen.</p>
     *
     * <p>Without {@code seconds}, the job runs the edition's budget — duration
     * and plateau, {@code GET /api/parametres-solveur}. With it, that duration
     * instead, refused in {@code 400} above the instance's ceiling.</p>
     */
    @POST
    @Path("/solve/async/reference-data")
    @Consumes(MediaType.WILDCARD)
    public Response solveFromReferenceData(
            @QueryParam("seconds") Long secondsLimit,
            @QueryParam("enFile") @DefaultValue("false") boolean enFile,
            @QueryParam("reamorcage") String reamorcage) {
        SolverJob job =
                solverJobService.submitSolveFromReferenceData(secondsLimit, enFile, Reamorcage.parse(reamorcage));
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
    public Response solveIncremental(
            ReplanificationScope scope,
            @QueryParam("seconds") Long secondsLimit,
            @QueryParam("enFile") @DefaultValue("false") boolean enFile) {
        SolverJob job = solverJobService.submitSolveIncremental(secondsLimit, scope, enFile);
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
        return solverJobService.fileAttente().stream()
                .map(JobView::withoutResult)
                .toList();
    }

    /**
     * Server-side "is the solver busy?" flag, polled by every browser so a
     * running job locks the UI even in another session or a private window.
     * Returns 204 when the solver is idle.
     */
    @GET
    @Path("/jobs/active")
    public Response activeJob() {
        return solverJobService
                .findActive()
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
        // Per connection, not per server: this method runs once per request, so
        // the cursor below belongs to exactly one subscriber and two clients
        // can be at two different points of the same curve.
        ScoreCursor scoreCursor = new ScoreCursor();
        // merging, not concatenating: both upstreams are subscribed to at once,
        // so no transition can slip through between the initial state and the
        // subscription to the broadcaster. The item(0) is what makes the first
        // event the current state.
        Multi<OutboundSseEvent> states = Multi.createBy()
                .merging()
                .streams(Multi.createFrom().item(0L), jobStream.changes())
                // The state is read and serialised off the publishing thread,
                // which holds the solver service monitor while it announces a
                // transition, and off the event loop.
                .emitOn(Infrastructure.getDefaultWorkerPool())
                // A client too slow to keep up gets the latest state, never a
                // backlog of stale ones: each event is a full snapshot.
                .onOverflow()
                .dropPreviousItems()
                .map(version -> stateEvent());
        Multi<OutboundSseEvent> heartbeats = Multi.createFrom()
                .ticks()
                .every(configJobStream.heartbeat())
                .onOverflow()
                .drop()
                .map(tick -> heartbeatEvent());
        // The score curve beats on its own clock rather than on the job
        // transitions: a running solve improves constantly and transitions
        // almost never, so there is nothing to hang these events on. A tick
        // that has nothing new to say emits nothing at all.
        Multi<OutboundSseEvent> scores = Multi.createFrom()
                .ticks()
                .every(configJobStream.score())
                .onOverflow()
                .drop()
                .emitOn(Infrastructure.getDefaultWorkerPool())
                // Zero or one event per tick, expressed as a list rather than a
                // nullable mapping: Mutiny rejects a mapper returning null, and
                // a downstream filter would already be too late.
                .onItem()
                .transformToIterable(tick -> scoreEvents(scoreCursor));
        return Multi.createBy().merging().streams(states, heartbeats, scores);
    }

    /**
     * How far along the curve one open connection has been served. Mutable and
     * owned by a single subscription, which is what lets the {@code score}
     * events be deltas: a full series would be re-sent every second and would
     * grow with the run.
     */
    private static final class ScoreCursor {

        /** Generation of the series this cursor describes; see {@link #GENERATION_ABSENTE}. */
        private int generation = GENERATION_ABSENTE;
        /** Number of points already sent for that generation. */
        private int envoyes;
        /** Whether anything at all was sent for it — an empty curve is still news. */
        private boolean amorce;
        /** Last {@code termine} flag sent, so the end of a run is pushed too. */
        private boolean termine;
    }

    /**
     * The points this connection has not seen yet, as an event — or nothing at
     * all when there is nothing to say. A generation change (a new run, or a
     * decimation of the series — see {@code SolverScoreTrace}) restarts the
     * cursor at zero, and the client replaces its series whenever
     * {@code depuis} is 0 rather than appending to it.
     *
     * <p>The edition is on the wire and the filtering is the client's, exactly
     * as it already is for {@code JobView.editionId} on the {@code state}
     * events: {@code EventSource} cannot send the {@code X-Edition-Id} header,
     * so a stream has no edition of its own. The request-scoped read,
     * {@code GET /api/jobs/score}, does carry the header and refuses a curve
     * belonging to another edition server-side.</p>
     */
    private List<OutboundSseEvent> scoreEvents(ScoreCursor cursor) {
        SolverScoreTrace.Trace trace = scoreTrace.snapshot();
        if (trace == null) {
            return absenceEvent(cursor);
        }
        if (trace.generation() != cursor.generation) {
            cursor.generation = trace.generation();
            cursor.envoyes = 0;
            cursor.amorce = false;
        }
        int depuis = Math.min(cursor.envoyes, trace.points().size());
        List<SolverScoreTrace.Point> nouveaux =
                trace.points().subList(depuis, trace.points().size());
        // A running solve beats every tick even with nothing new to add: its
        // dureeMs is what draws the plateau, and a plateau is precisely the
        // state that produces no new point at all. A finished curve goes quiet.
        boolean rienDeNeuf = nouveaux.isEmpty() && cursor.amorce && cursor.termine == trace.termine();
        if (rienDeNeuf && trace.termine()) {
            return List.of();
        }
        cursor.envoyes = trace.points().size();
        cursor.amorce = true;
        cursor.termine = trace.termine();
        return List.of(scoreEvent(new ScoreDelta(
                trace.jobId(),
                trace.editionId(),
                trace.generation(),
                depuis,
                trace.intervalleMs(),
                trace.dureeMs(),
                trace.termine(),
                List.copyOf(nouveaux))));
    }

    /**
     * "There is no curve" — a {@code score} event with no {@code jobId}, sent
     * once per connection.
     *
     * <p>The case it exists for is a restart. The client holds a curve whose
     * last event said {@code termine: false}; the server it reconnects to has
     * no trace at all, and silence would leave that curve on screen looking
     * live forever, for a run the server has already reported as
     * {@code INTERROMPU}. Nothing else can produce it: a trace is never
     * un-created within one JVM.</p>
     */
    private List<OutboundSseEvent> absenceEvent(ScoreCursor cursor) {
        if (cursor.amorce && cursor.generation == GENERATION_ABSENTE) {
            return List.of();
        }
        cursor.generation = GENERATION_ABSENTE;
        cursor.envoyes = 0;
        cursor.amorce = true;
        cursor.termine = false;
        return List.of(scoreEvent(new ScoreDelta(null, null, GENERATION_ABSENTE, 0, 0, 0, false, List.of())));
    }

    private OutboundSseEvent scoreEvent(ScoreDelta delta) {
        return sse.newEventBuilder()
                .name(EVENT_SCORE)
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(ScoreDelta.class, delta)
                .build();
    }

    /**
     * The score curve of the running solve, in full (issue #304).
     *
     * <p>Read once, when the Solveur page opens — the curve is not polled: a
     * new stream connection is sent the whole series in its first {@code score}
     * event, and the client already reopens that stream on error and after 45 s
     * of silence. What this buys is the window before that, and a browser with
     * no {@code EventSource} at all.</p>
     *
     * <p>It is also the only read of the curve that carries an edition, hence
     * the only one that can refuse it: a curve belonging to another edition
     * answers 204, like an idle solver.</p>
     */
    @GET
    @Path("/jobs/score")
    public Response scoreCurve() {
        SolverScoreTrace.Trace trace = scoreTrace.snapshot();
        if (trace == null || !Objects.equals(trace.editionId(), editionContext.editionIdCourant())) {
            return Response.noContent().build();
        }
        return Response.ok(trace).build();
    }

    private OutboundSseEvent stateEvent() {
        JobsState state = new JobsState(
                solverJobService.findActive().map(JobView::withoutResult).orElse(null),
                solverJobService.fileAttente().stream()
                        .map(JobView::withoutResult)
                        .toList());
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
        return solverJobService
                .find(id)
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
     * Stops a job started by mistake. A running solve is terminated early (the
     * Timefold solver returns its best solution so far, which is still
     * persisted and analysed as usual) instead of being killed outright.
     */
    @POST
    @Path("/jobs/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    public Response cancelJob(@PathParam("id") String id) {
        return solverJobService
                .cancel(id)
                .map(job -> Response.ok(JobView.withoutResult(job)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * One server-sent event's worth of solver state: what holds the solver, and
     * what waits behind it. The two used to be two requests; joining them is
     * what removes the second one.
     */
    public record JobsState(JobView active, List<JobView> file) {}

    /** Payload of a heartbeat: a timestamp, so the event is never empty. */
    public record Heartbeat(Instant at) {}

    /**
     * One {@code score} event: the points of the curve this connection had not
     * received yet.
     *
     * @param jobId        {@code null} means "there is no curve any more" —
     *                      see {@link #absenceEvent}
     * @param depuis       index the first point of {@code points} has in the
     *                     series — 0 means "replace what you hold", anything
     *                     else means "append at that index"
     * @param intervalleMs current sampling interval, which doubles every time
     *                     the series is decimated
     * @param dureeMs      how long the run has been going: the curve's right
     *                     edge, which the points alone cannot give since
     *                     Timefold only announces strict improvements
     * @param termine      whether the run is over and the curve final
     */
    public record ScoreDelta(
            String jobId,
            String editionId,
            int generation,
            int depuis,
            long intervalleMs,
            long dureeMs,
            boolean termine,
            List<SolverScoreTrace.Point> points) {}

    public record JobView(
            /**
             * Why the request was refused, in the operator's language — set only
             * on the {@code 409} of {@link SolverOccupeMapper}, absent from the
             * job payloads themselves. {@code api.service.ts} reads
             * {@code body.message} and nothing else, so a conflict without it
             * reaches the screen as "Échec de la requête (code 409)".
             */
            @JsonInclude(JsonInclude.Include.NON_NULL) String message,
            String id,
            String type,
            String status,
            String editionId,
            String editionNom,
            Long secondsLimit,
            /** The feasible-plateau bailout the job runs under, 0 for none; null on a job queued before it was stored. */
            Long plateauSeconds,
            /**
             * The duration the edition stored where the instance's ceiling cut
             * it — a ceiling lowered since, or a scenario imported above it —
             * or null when it ran as stored; the ceiling is secondsLimit.
             * Values rather than a sentence, so the screen says it in its
             * reader's language.
             */
            Long cappedFromSecondsLimit,
            /** Same for the plateau, whose ceiling is plateauSeconds. */
            Long cappedFromPlateauSeconds,
            /** Where a full solve was asked to start from (issue #174); null for an incremental job. */
            Reamorcage reamorcage,
            Instant submittedAt,
            Instant startedAt,
            Instant finishedAt,
            long elapsedSeconds,
            String error,
            Object result) {

        public static JobView withoutResult(SolverJob job) {
            return build(job, null, null);
        }

        static JobView withResult(SolverJob job) {
            return build(job, job.getResult(), null);
        }

        /** The refused-because-busy form: same job, plus a sentence a human can read. */
        public static JobView conflit(SolverJob job, String message) {
            return build(job, null, message);
        }

        private static JobView build(SolverJob job, Object result, String message) {
            return new JobView(
                    message,
                    job.getId(),
                    job.getType().name(),
                    job.getStatus().name(),
                    job.getEditionId(),
                    job.getEditionNom(),
                    job.getSecondsLimit(),
                    job.getPlateauSeconds(),
                    job.getCappedFrom() == null ? null : job.getCappedFrom().secondsLimit(),
                    job.getCappedFrom() == null ? null : job.getCappedFrom().plateauSeconds(),
                    job.getReamorcage(),
                    job.getSubmittedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt(),
                    job.getElapsedSeconds(),
                    job.getError(),
                    result);
        }
    }
}
