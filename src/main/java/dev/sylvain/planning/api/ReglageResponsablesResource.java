package dev.sylvain.planning.api;

import dev.sylvain.planning.service.responsable.ReglageResponsableService;
import dev.sylvain.planning.service.responsable.ReglagesResponsables;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the responsables de stand of the edition read: names or shifts only, by
 * default and stand by stand. An edition's setting, like the rest of the
 * edition's Paramètres.
 */
@Path("/reglage-responsables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReglageResponsablesResource {

    private final ReglageResponsableService reglages;

    @Inject
    public ReglageResponsablesResource(ReglageResponsableService reglages) {
        this.reglages = reglages;
    }

    @GET
    public ReglagesResponsables get() {
        return reglages.get();
    }

    /** The administrators' alone: showing names to a responsable is their decision. */
    @PUT
    @InstanceOnly
    public ReglagesResponsables put(ReglagesResponsables reglage) {
        return reglages.replace(reglage);
    }
}
