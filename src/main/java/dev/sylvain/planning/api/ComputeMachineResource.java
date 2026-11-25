package dev.sylvain.planning.api;

import dev.sylvain.planning.service.solve.RemoteSolves;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Whether a solve may run on the permanent worker instead of here (ADR 0086),
 * and where it runs by default: what the Solveur screen needs to offer the choice.
 * Nothing here starts or stops anything — the machine on demand is gone (ADR 0101).
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
}
