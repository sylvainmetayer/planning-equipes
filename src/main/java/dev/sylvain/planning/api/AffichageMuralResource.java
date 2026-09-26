package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.mural.AffichageMuralLink;
import dev.sylvain.planning.service.mural.AffichageMuralLinkRequest;
import dev.sylvain.planning.service.mural.AffichageMuralService;
import dev.sylvain.planning.service.mural.AffichageMuralView;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The wall display links of the current edition, managed from the Paramètres
 * screen: list, create, revoke — behind the admin session like the rest of
 * {@code /api}. What a link opens is {@link MuralResource}, under a prefix of
 * its own; the admin's own print of a day is {@link #preview}, here, behind
 * the session — never under that prefix.
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

    /**
     * The wall view of one day, for « Imprimer cette journée » on the Planning
     * page: what a link opens, read through the admin session rather than a
     * token. Deliberately not under {@code /api/mural/}: that prefix is what an
     * access proxy lets through unauthenticated, and it names one route.
     *
     * @param date {@code AAAA-MM-JJ}; absent, the journée under way
     */
    @GET
    @Path("/apercu")
    public RestResponse<AffichageMuralView> preview(@QueryParam("date") String date) {
        return RestResponse.ResponseBuilder.ok(service.preview(parseDate(date)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Date invalide : " + date);
        }
    }
}
