package dev.sylvain.planning.api;

import dev.sylvain.planning.service.solve.RemoteSolves;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The machine a solve may run on instead of here (ADR 0086): what the Solveur
 * screen shows of it, and the two gestures an operator has — starting it ahead
 * of a session, and stopping it without waiting for its idle time.
 *
 * <p>Nothing here configures it: where the worker is, and the launcher that
 * starts it, are instance settings.</p>
 */
@InstanceOnly
@Path("/machine-calcul")
@Produces(MediaType.APPLICATION_JSON)
public class ComputeMachineResource {

    private final RemoteSolves remoteSolves;

    @Inject
    public ComputeMachineResource(RemoteSolves remoteSolves) {
        this.remoteSolves = remoteSolves;
    }

    @GET
    public RemoteSolves.ComputeMachine get() {
        return remoteSolves.view();
    }

    /** Asks the launcher for the machine now, so the first solve of the session does not wait for it. */
    @POST
    @Path("/preparer")
    @Consumes(MediaType.WILDCARD)
    public RemoteSolves.ComputeMachine prepare() {
        remoteSolves.prepare();
        return remoteSolves.view();
    }

    /** Stops the machine now; refused in {@code 409} while it solves. */
    @POST
    @Path("/arreter")
    @Consumes(MediaType.WILDCARD)
    public RemoteSolves.ComputeMachine stop() {
        remoteSolves.stopNow();
        return remoteSolves.view();
    }
}
