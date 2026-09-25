package dev.sylvain.planning.api;

import dev.sylvain.planning.service.mural.AffichageMuralLink;
import dev.sylvain.planning.service.mural.AffichageMuralLinkRequest;
import dev.sylvain.planning.service.mural.AffichageMuralService;
import dev.sylvain.planning.service.mural.CreatedAffichageMuralLink;
import dev.sylvain.planning.service.mural.QrCodeRequest;
import dev.sylvain.planning.service.mural.QrCodeView;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The wall display links of the current edition, managed from the Paramètres
 * screen: list, create, revoke — behind the admin session like the rest of
 * {@code /api}. What a link opens is {@link MuralResource}, under a prefix of
 * its own.
 */
@Path("/affichage-mural")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AffichageMuralResource {

    private final AffichageMuralService service;

    @Inject
    public AffichageMuralResource(AffichageMuralService service) {
        this.service = service;
    }

    /** The live links, with the last time a screen read each one. Never a token. */
    @GET
    public List<AffichageMuralLink> list() {
        return service.list();
    }

    /** Creates a link; the answer carries its token, which is never readable again. */
    @POST
    public RestResponse<CreatedAffichageMuralLink> create(AffichageMuralLinkRequest request) {
        return RestResponse.ResponseBuilder.create(Response.Status.CREATED, service.create(request))
                .header("Cache-Control", "no-store")
                .build();
    }

    /** Revokes a link: its screen falls on the dead-link page at its next read. */
    @DELETE
    @Path("/{id}")
    public void revoke(@PathParam("id") long id) {
        service.revoke(id);
    }

    /** The QR code of the address just created, as a grid the screen draws. Writes nothing. */
    @POST
    @Path("/qr-code")
    public QrCodeView qrCode(QrCodeRequest request) {
        return service.qrCode(request);
    }
}
