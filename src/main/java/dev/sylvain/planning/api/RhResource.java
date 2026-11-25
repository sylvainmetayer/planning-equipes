package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.RapportConformite;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.rh.RhService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * What the RH right reads: the compliance report of an edition (JSON for the
 * screen, PDF and CSV to take away) and the list of the editions it may read.
 * Transport only — the right, the edition and the journal are
 * {@link RhService}'s.
 *
 * <p>The edition is in the <b>path</b>, never the {@code X-Edition-Id} header:
 * an account whose only right is an RH one is no organiser of any edition, and
 * the header would be refused for it. {@code @RolesAllowed} sits on each method,
 * not on the class, like {@link ResponsableResource}.</p>
 */
@Path("/rh")
public class RhResource {

    @Inject
    RhService rh;

    @Inject
    SecurityIdentity identity;

    @GET
    @RolesAllowed({"rh", "admin"})
    @Path("/editions")
    @Produces("application/json")
    public List<RhService.EditionAccessible> editions() {
        return rh.accessibleEditions(compte(), admin());
    }

    @GET
    @RolesAllowed({"rh", "admin"})
    @Path("/editions/{editionId}/conformite")
    @Produces("application/json")
    public RapportConformite rapport(@PathParam("editionId") String editionId) {
        return rh.rapport(compte(), admin(), editionId);
    }

    @GET
    @RolesAllowed({"rh", "admin"})
    @Path("/editions/{editionId}/conformite.pdf")
    @Produces("application/pdf")
    public Response exportPdf(@PathParam("editionId") String editionId) {
        return Response.ok(rh.pdf(compte(), admin(), editionId))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rapport-conformite.pdf\"")
                .build();
    }

    @GET
    @RolesAllowed({"rh", "admin"})
    @Path("/editions/{editionId}/conformite.csv")
    @Produces("text/csv")
    public Response exportCsv(@PathParam("editionId") String editionId) {
        return CsvDownload.attachment(rh.csv(compte(), admin(), editionId), "rapport-conformite.csv");
    }

    private Compte compte() {
        return identity.getAttribute(Compte.IDENTITY_ATTRIBUTE);
    }

    private boolean admin() {
        return identity.hasRole("admin");
    }
}
