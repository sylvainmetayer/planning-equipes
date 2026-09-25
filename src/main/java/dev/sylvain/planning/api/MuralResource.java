package dev.sylvain.planning.api;

import dev.sylvain.planning.service.mural.AffichageMuralService;
import dev.sylvain.planning.service.mural.AffichageMuralView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The wall display of the control room — the one route its token opens.
 *
 * <p><b>Why a prefix of its own.</b> Same reasoning as the calendar
 * subscription (ADR 0019), carried over by ADR 0053: {@code /api/mural/*} is
 * excepted from the admin authentication, and an access proxy in front of the
 * deployment needs a URL pattern that names this route and nothing else. One
 * route lives under it; a second one would silently widen what a photographed
 * television opens.</p>
 *
 * <p><b>What the token opens, exactly</b>: this read, for its own edition — the
 * stands of the day, who holds them, the alerts. No referential, no plan
 * export, no espace, no write. Unknown and revoked tokens get the same 404,
 * with the same sentence. The answer is {@code no-store}: it names people and
 * changes every minute; the {@code X-Robots-Tag} every response carries is
 * {@code SecurityHeadersFilter}'s.</p>
 *
 * <p>Rate-limited per address before it gets here
 * ({@link AffichageMuralRateLimiter}), and never journalled: a screen reads it
 * every sixty seconds, and the history is not an access log.</p>
 */
@Path("/mural")
public class MuralResource {

    private final AffichageMuralService service;

    @Inject
    public MuralResource(AffichageMuralService service) {
        this.service = service;
    }

    @GET
    @Path("/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public RestResponse<AffichageMuralView> view(@PathParam("token") String token) {
        return RestResponse.ResponseBuilder.ok(service.view(token))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
