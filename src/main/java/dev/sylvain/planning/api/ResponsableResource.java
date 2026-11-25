package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.responsable.ResponsableService;
import dev.sylvain.planning.service.responsable.ResponsableView;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * The responsable de stand's reads — reads only: no method here writes, and
 * {@code ResponsableProjectionStructurelleTest} refuses one.
 *
 * <p>Two floors. The HTTP policy {@code role-responsable} and
 * {@code @RolesAllowed} let in only an identity carrying
 * {@code responsable_stand}, which {@code CompteIdentityAugmentor} grants to an
 * active account holding such a right in force — deny by default for everyone
 * else, administrators included. {@link ResponsableService} then scopes every
 * read to the rights of <b>that</b> account on the <b>active</b> edition: no
 * edition and no stand is chosen by the caller (no {@code X-Edition-Id}). The
 * screen's hiding of what it cannot show is comfort, never a protection.</p>
 */
@Path("/responsable")
public class ResponsableResource {

    /** On each method, not on the class: a class-level role is how the administrators' resources are told apart. */
    private static final String ROLE = "responsable_stand";

    @Inject
    ResponsableService responsables;

    @Inject
    SecurityIdentity identity;

    /** The caller's stands on the active edition, with their published shifts. */
    @GET
    @RolesAllowed(ROLE)
    @Path("/stands")
    @Produces("application/json")
    public ResponsableView stands() {
        return responsables.view(compte());
    }

    /** The printable sheet of one of the caller's stands: the same names, or none, as the screen. */
    @GET
    @RolesAllowed(ROLE)
    @Path("/stands/{standId}/affichette.pdf")
    @Produces("application/pdf")
    public Response affichette(@PathParam("standId") String standId) {
        return Response.ok(responsables.affichette(compte(), standId))
                .header("Content-Disposition", "inline; filename=\"affichette-stand.pdf\"")
                .build();
    }

    private Compte compte() {
        return identity.getAttribute(Compte.IDENTITY_ATTRIBUTE);
    }
}
