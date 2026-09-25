package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.journal.Acteur;
import dev.sylvain.planning.service.journal.ActionJournalisee;
import dev.sylvain.planning.service.journal.CatalogueActions;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.journal.PayloadIds;
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

    private final JournalActionService journal;

    private final CurrentAction currentAction;

    private final EditionRequestScope requestScope;

    @Inject
    public JournalActionFilter(
            JournalActionService journal, CurrentAction currentAction, EditionRequestScope requestScope) {
        this.journal = journal;
        this.currentAction = currentAction;
        this.requestScope = requestScope;
    }

    @Override
    public void filter(ContainerRequestContext requete, ContainerResponseContext reponse) {
        String cle = key();
        Optional<ActionJournalisee> catalogue = CatalogueActions.forRoute(cle);
        if (catalogue.isEmpty()) {
            return;
        }
        boolean prouve = requestScope.isIdentityProven();
        if (CatalogueActions.recordedOnlyWhenProven(cle) && !prouve) {
            // A download refused before anybody proved who they are: a free,
            // repeatable read, and writing it would hand the table to anyone.
            return;
        }
        // A route whose method serves several actions says which one it was.
        Optional<ActionJournalisee> action =
                CatalogueActions.forCode(currentAction.action()).or(() -> catalogue);
        TokenOwner proprietaire = requestScope.getTokenOwner();
        String animateurId = proprietaire == null ? null : proprietaire.animateurId();
        Acteur acteur = acteur(prouve);
        journal.recordAction(
                action.get(),
                acteur,
                switch (acteur) {
                    case ANIMATEUR -> animateurId;
                    // Nobody proved who they were: no name to write, not even « admin ».
                    case ANONYME -> null;
                    default -> journal.nomAdmin();
                },
                entiteId(requete, reponse, animateurId),
                currentAction.champs(),
                reponse.getStatus());
    }

    /**
     * Who acted. On the open routes — the espace and the calendar feed — the
     * animateur only when the request <b>proved</b> it: a live session opened
     * by the e-mail code, the code just exchanged for one, a proxy asserting
     * the fiche's own address, or the dedicated subscription token (see
     * {@link EditionRequestScope#isIdentityProven()}). Holding the espace link
     * is not that proof: it can be forwarded or found, so a wrong code, a
     * code request or a refused download is filed as ANONYME — pinning it on
     * the animateur, often a minor, would blame them for someone else's
     * attempt with their link. The animateur the token designates stays the
     * <em>target</em> ({@code entite_id}), which is what an operator needs to
     * see. Everywhere else the admin session is the only way in.
     *
     * <p>Decided on the proof, never on the response status: a wrong code is
     * a 400, a code request throttled is a 429, and neither says anything
     * about who is asking.</p>
     */
    private Acteur acteur(boolean prouve) {
        if (!openRoute()) {
            return Acteur.ADMIN;
        }
        return prouve ? Acteur.ANIMATEUR : Acteur.ANONYME;
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
        String cree = PayloadIds.of(reponse.getEntity());
        return cree != null ? cree : animateurId;
    }

    private static String first(Map<String, List<String>> params, String nom) {
        List<String> valeurs = params.get(nom);
        return valeurs == null || valeurs.isEmpty() ? null : valeurs.get(0);
    }
}
