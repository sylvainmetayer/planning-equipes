package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.responsable.EditionResponsable;
import dev.sylvain.planning.service.responsable.ResponsableService;
import dev.sylvain.planning.service.responsable.ResponsableView;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The responsable de stand's reads (issue #295, ADR 0077) — reads only: no
 * method here writes, and {@code ResponsableProjectionStructurelleTest}
 * refuses one.
 *
 * <p>Three floors, of which two are security. The HTTP policy
 * {@code role-responsable} lets in only an identity carrying
 * {@code responsable-stand}, which {@code CompteIdentityAugmentor} grants to
 * an active account holding such a right in force — deny by default for
 * everyone else, administrators included. {@link ResponsableService} then
 * scopes every read to the rights of <b>that</b> account on the edition asked
 * for. The screen's hiding of what it cannot show is comfort, never a
 * protection.</p>
 *
 * <p>The edition travels in the path, not in the {@code X-Edition} header the
 * administration uses: it is a scope checked against the caller's rights,
 * not a preference the caller sets.</p>
 */
@Path("/responsable")
@Produces(MediaType.APPLICATION_JSON)
public class ResponsableResource {

    @Inject
    ResponsableService responsables;

    @Inject
    SecurityIdentity identity;

    /** The editions where the caller is responsable de stand today, default one first. */
    @GET
    @Path("/editions")
    public List<EditionResponsable> editions() {
        return responsables.editions(compte());
    }

    /**
     * The published plan of the caller's stands on one edition, or of one of
     * them. 404, with one message, for an edition or a stand out of reach and
     * for one that does not exist.
     */
    @GET
    @Path("/editions/{editionId}")
    public ResponsableView view(@PathParam("editionId") String editionId, @QueryParam("stand") String standId) {
        return responsables.view(compte(), editionId, standId == null || standId.isBlank() ? null : standId);
    }

    private Compte compte() {
        return identity == null ? null : identity.getAttribute(CompteIdentityAugmentor.ATTRIBUT_COMPTE);
    }
}
