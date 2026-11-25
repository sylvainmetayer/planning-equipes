package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;

/**
 * The write net of the lecteur right (ADR 0099): an account holding it is read
 * only in the edition its request designates, whatever route it calls — 403 on
 * every verb but {@code GET}, {@code HEAD} and {@code OPTIONS}, and on a
 * {@link NotForLecteur} read, unless the route is an {@link ExportForLecteur}.
 *
 * <p>The decision is per edition: an account that is a lecteur of one edition
 * and an organiser of another writes in the second ({@link
 * CompteService#writeScope}). An account with no lecteur right never reaches
 * the refusal, so nothing changes for an organiser or an administrator. The
 * requests with no designated edition are judged on the rights that cover all
 * editions.</p>
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class LecteurFilter implements ContainerRequestFilter {

    private static final Set<String> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    /**
     * The resources behind another door than the organiser's ({@code role-gestion}): the session's own
     * routes (logout, password), the token-opened ones, and the RH and responsable prefixes, whose rights
     * a lecteur right neither gives nor takes away. Refusing a lecteur there would lock it out of its own
     * logout, of an espace it is the animateur of, and of the contacts its RH right writes.
     */
    static final Set<Class<?>> OTHER_DOORS = Set.of(
            AuthResource.class,
            EspaceAnimateurResource.class,
            AbonnementIcsResource.class,
            MuralResource.class,
            RhResource.class,
            ResponsableResource.class);

    static final String MESSAGE = "Ce compte lit cette édition sans pouvoir la modifier ni lancer de calcul.";

    @Context
    ResourceInfo resource;

    private final SecurityIdentity identity;

    @Inject
    public LecteurFilter(SecurityIdentity identity) {
        this.identity = identity;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (identity.isAnonymous()
                || identity.hasRole("admin")
                || resource.getResourceMethod() == null
                || OTHER_DOORS.contains(resource.getResourceClass())) {
            return;
        }
        Compte compte = identity.getAttribute(Compte.IDENTITY_ATTRIBUTE);
        Instant now = Instant.now();
        if (compte == null || !CompteService.isLecteur(compte, now)) {
            return;
        }
        String edition = requestContext.getHeaderString(EditionContext.HEADER);
        if (CompteService.writeScope(compte, now).test(edition == null || edition.isBlank() ? null : edition.trim())) {
            return;
        }
        if (!opens(requestContext.getMethod(), resource.getResourceMethod())) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity(new ValidationError(MESSAGE))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /** What a lecteur may call: a read that is not refused, or an export rendered from what they send. */
    static boolean opens(String verb, Method method) {
        return READS.contains(verb)
                ? !method.isAnnotationPresent(NotForLecteur.class)
                : method.isAnnotationPresent(ExportForLecteur.class);
    }
}
