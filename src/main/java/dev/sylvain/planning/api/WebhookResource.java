package dev.sylvain.planning.api;

import dev.sylvain.planning.service.webhook.WebhookService;
import dev.sylvain.planning.service.webhook.WebhookService.WebhookDeliveryView;
import dev.sylvain.planning.service.webhook.WebhookService.WebhookRequest;
import dev.sylvain.planning.service.webhook.WebhookService.WebhookSaved;
import dev.sylvain.planning.service.webhook.WebhookService.WebhookSecret;
import dev.sylvain.planning.service.webhook.WebhookService.WebhookTestResult;
import dev.sylvain.planning.service.webhook.WebhookService.WebhookView;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.ResponseStatus;

/**
 * The outgoing webhooks of the instance (ADR 0074), as Paramètres › Instance
 * configures them. Admin only, like everything under {@code /api}; no MCP
 * tool mirrors it — see {@code docs/mcp.md}.
 *
 * <p>No secret is ever read back: the HMAC key is in the answer of the call
 * that generated it and nowhere else, and the address of a chat preset is
 * returned masked.</p>
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
public class WebhookResource {

    private final WebhookService webhookService;

    @Inject
    public WebhookResource(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GET
    public List<WebhookView> list() {
        return webhookService.list();
    }

    /** Creates a webhook; a generic one comes back with its HMAC secret, shown this once. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(201)
    public WebhookSaved create(WebhookRequest request) {
        return webhookService.create(request);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public WebhookSaved update(@PathParam("id") String id, WebhookRequest request) {
        return webhookService.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        webhookService.delete(id);
        return Response.noContent().build();
    }

    /** A new HMAC secret, shown this once; the previous one stops signing at once. */
    @POST
    @Path("/{id}/secret")
    public WebhookSecret regenerateSecret(@PathParam("id") String id) {
        return webhookService.regenerateSecret(id);
    }

    /** Sends the {@code test} event now and answers how it went: status code, duration, error. */
    @POST
    @Path("/{id}/test")
    public WebhookTestResult test(@PathParam("id") String id) {
        return webhookService.test(id);
    }

    @GET
    @Path("/{id}/livraisons")
    public List<WebhookDeliveryView> deliveries(@PathParam("id") String id) {
        return webhookService.deliveries(id);
    }

    /** Puts a delivery back at the start of its schedule, under the same id. */
    @POST
    @Path("/livraisons/{id}/renvoi")
    public WebhookDeliveryView resend(@PathParam("id") String id) {
        return webhookService.resend(id);
    }
}
