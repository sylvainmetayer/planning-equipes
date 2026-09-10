package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.journal.Acteur;
import dev.sylvain.planning.service.journal.ActionJournalisee;
import dev.sylvain.planning.service.journal.CatalogueActions;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.journal.JournalActionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one line of the history for every REST action that has one
 * (issue #406).
 *
 * <p>A <b>response</b> filter, not a request one: the line says how the action
 * ended, and « refusé » is as much a fact worth keeping as « fait » — a
 * rejected import tells the story of the morning better than its absence
 * would. Unbound like {@code EditionHeaderFilter}, so it sees every route and
 * a new one cannot escape it by omission; what it does with a route is decided
 * by {@link CatalogueActions}, and
 * {@code JournalCoverageStructurelleTest} fails on a write route that is in
 * neither the catalogue nor its argued exclusion list.</p>
 *
 * <p>It never sees the MCP tools — {@code quarkus-mcp-server-http} answers
 * before the JAX-RS chain — which is why those carry their own interceptor.</p>
 */
@Provider
public class JournalActionFilter implements ContainerResponseFilter {

    /**
     * Path parameters that name what an action bears upon. {@code jeton} is
     * deliberately absent: an espace token is a credential, and the animateur
     * it designates is read from the resolved owner instead.
     */
    private static final List<String> PARAMS_ENTITE =
            List.of("id", "animateurId", "standId", "creneauId", "posteId", "name");

    @Context
    ResourceInfo resourceInfo;

    @Inject
    JournalActionService journal;

    @Inject
    CurrentAction currentAction;

    @Inject
    EditionRequestScope requestScope;

    @Override
    public void filter(ContainerRequestContext requete, ContainerResponseContext reponse) {
        Optional<ActionJournalisee> catalogue = CatalogueActions.forRoute(key());
        if (catalogue.isEmpty()) {
            return;
        }
        // A route whose method serves several actions says which one it was.
        Optional<ActionJournalisee> action =
                CatalogueActions.forCode(currentAction.action()).or(() -> catalogue);
        TokenOwner proprietaire = requestScope.getTokenOwner();
        String animateurId = proprietaire == null ? null : proprietaire.animateurId();
        Acteur acteur = acteur(animateurId);
        journal.record(
                action.get(),
                acteur,
                acteur == Acteur.ANIMATEUR ? animateurId : journal.nomAdmin(),
                entiteId(requete, reponse, animateurId),
                currentAction.champs(),
                reponse.getStatus());
    }

    /**
     * Who acted. A resolved espace token names an animateur; on the espace
     * routes without one, nobody — those routes are open, so a bad or expired
     * token reaches the refusal, and calling that « Administration » would
     * misfile the very rows an operator goes looking for. Everywhere else the
     * admin session is the only way in.
     */
    private Acteur acteur(String animateurId) {
        if (animateurId != null) {
            return Acteur.ANIMATEUR;
        }
        return openRoute() ? Acteur.ANONYME : Acteur.ADMIN;
    }

    /**
     * Whether the matched route is one of the two an unauthenticated caller
     * may reach (see {@code quarkus.http.auth.permission.*}). Read off the
     * resource class rather than off the identity: under {@code %test} the
     * admin API is {@code permit}, so an anonymous identity there proves
     * nothing about production.
     */
    private boolean openRoute() {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return false;
        }
        String classe = resourceInfo.getResourceClass().getSimpleName();
        return classe.equals("EspaceAnimateurResource") || classe.equals("AbonnementIcsResource");
    }

    /** {@code SimpleClassName#methodName}, or {@code null} when no resource matched (a 404). */
    private String key() {
        if (resourceInfo == null
                || resourceInfo.getResourceClass() == null
                || resourceInfo.getResourceMethod() == null) {
            return null;
        }
        return resourceInfo.getResourceClass().getSimpleName() + "#"
                + resourceInfo.getResourceMethod().getName();
    }

    /**
     * What the action bore upon: the path parameter naming it, else the id of
     * what the call returned — a creation
     * has no id in its URL, and « stand créé » without saying which one is
     * half a line — else, on the espace routes, the animateur the token
     * resolved to.
     */
    private String entiteId(ContainerRequestContext requete, ContainerResponseContext reponse, String animateurId) {
        MultivaluedMap<String, String> params = requete.getUriInfo().getPathParameters();
        for (String nom : PARAMS_ENTITE) {
            String valeur = first(params, nom);
            if (valeur != null) {
                return valeur;
            }
        }
        String cree = idOfPayload(reponse.getEntity());
        return cree != null ? cree : animateurId;
    }

    /**
     * The id of what a creation returned, read reflectively from the payload —
     * either the object itself, or the single business component of a wrapper
     * like {@code WrittenAnimateur}.
     *
     * <p>Reflection rather than a per-route rule because there is no shape all
     * these payloads share, and swallowing everything because a trace must
     * never cost an action: an unreadable payload leaves the id blank, it does
     * not fail the request that already succeeded.</p>
     */
    private static String idOfPayload(Object payload) {
        if (payload == null) {
            return null;
        }
        String direct = readId(payload);
        if (direct != null) {
            return direct;
        }
        if (payload instanceof Record) {
            for (var composant : payload.getClass().getRecordComponents()) {
                try {
                    String imbrique = readId(composant.getAccessor().invoke(payload));
                    if (imbrique != null) {
                        return imbrique;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // One unreadable component is not the end of the search:
                    // a later one may well carry the id.
                    continue;
                }
            }
        }
        return null;
    }

    private static String readId(Object objet) {
        if (objet == null || objet instanceof String || objet instanceof Number || objet instanceof Boolean) {
            return null;
        }
        try {
            Object id = objet.getClass().getMethod("getId").invoke(objet);
            return id == null ? null : String.valueOf(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String first(Map<String, List<String>> params, String nom) {
        List<String> valeurs = params.get(nom);
        return valeurs == null || valeurs.isEmpty() ? null : valeurs.get(0);
    }
}
