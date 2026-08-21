package dev.sylvain.planning.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.spi.runtime.AuthenticationFailureEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Verrouillage du form login admin après une série d'échecs.
 *
 * <p>L'application n'a qu'un compte, {@code admin}, sans second facteur : une
 * seule paire d'identifiants ouvre les données personnelles de ~150 personnes,
 * mineurs compris. {@code /j_security_check} acceptait pourtant les tentatives
 * au rythme du réseau — alors que la révélation de la clé MCP, elle, se
 * verrouillait déjà au bout de cinq essais. C'est le même verrou, posé là où
 * il manquait le plus.</p>
 *
 * <h2>Comment un échec et un succès se reconnaissent</h2>
 *
 * <p>Les deux répondent une redirection vers la même page ({@code landing-page}
 * et {@code error-page} pointent au même endroit), donc ni le statut ni
 * {@code Location} ne les distinguent. Les deux côtés se lisent donc ailleurs,
 * et pas au même endroit :</p>
 *
 * <ul>
 * <li><b>l'échec</b> est l'{@link AuthenticationFailureEvent} de Quarkus
 * ({@code quarkus.security.events.enabled}), qui porte le
 * {@link RoutingContext} de la tentative — donc son adresse ;</li>
 * <li><b>le succès</b> se lit sur la requête elle-même. Quarkus a bien un
 * événement de connexion réussie ({@code FormAuthenticationEvent}), mais il ne
 * transporte que son propre type : aucun contexte HTTP, donc aucune adresse à
 * qui rendre son crédit. Une connexion réussie se reconnaît alors à ce qu'elle
 * produit — une identité posée sur la requête, un cookie de session dans la
 * réponse — et l'un des deux suffit.</li>
 * </ul>
 *
 * <p>La fenêtre court depuis le <b>dernier</b> échec : une requête bloquée
 * n'atteint jamais l'authentification, donc n'en produit pas de nouveau, et le
 * verrou se lève bien {@code duree-blocage} après la dernière tentative
 * réelle.</p>
 *
 * <p>En mémoire et par adresse : voir {@link #adresse(RoutingContext)} pour ce
 * que « adresse » veut dire derrière un proxy, et {@code docs/securite.md}
 * pour ce qui reste au reverse proxy.</p>
 */
@ApplicationScoped
public class LimiteurConnexionsAdmin {

    /** Cible du form login Quarkus ({@code quarkus.http.auth.form.post-location} par défaut). */
    static final String CHEMIN_CONNEXION = "/j_security_check";

    /** Après les en-têtes de sécurité, avant tout traitement de la requête. */
    private static final int PRIORITE = 250;

    @ConfigProperty(name = "planning.auth.connexion.max-echecs")
    int maxEchecs;

    @ConfigProperty(name = "planning.auth.connexion.duree-blocage")
    Duration dureeBlocage;

    /** Lu de la configuration pour que les deux ne dérivent jamais l'un de l'autre. */
    @ConfigProperty(name = "quarkus.http.auth.form.cookie-name")
    String nomCookieSession;

    private final Map<String, Echecs> parAdresse = new ConcurrentHashMap<>();

    /** Échecs consécutifs d'une adresse, et l'instant du dernier. */
    private record Echecs(int nombre, Instant dernier) {
    }

    public void enregistrer(@Observes Filters filtres) {
        filtres.register(this::appliquer, PRIORITE);
    }

    private void appliquer(RoutingContext contexte) {
        if (!CHEMIN_CONNEXION.equals(contexte.normalizedPath())) {
            contexte.next();
            return;
        }
        String adresse = adresse(contexte);
        long attente = secondesDeBlocage(adresse);
        if (attente > 0) {
            contexte.response()
                    .setStatusCode(429)
                    .putHeader("Retry-After", String.valueOf(attente))
                    .putHeader("Content-Type", "application/json;charset=UTF-8")
                    .end("{\"message\":\"Trop de tentatives de connexion : réessayez dans "
                            + Math.max(1, (attente + 59) / 60) + " minute(s).\"}");
            return;
        }
        contexte.addEndHandler(issue -> {
            if (issue.succeeded() && connexionReussie(contexte)) {
                parAdresse.remove(adresse);
            }
        });
        contexte.next();
    }

    void surEchec(@Observes AuthenticationFailureEvent evenement) {
        Object contexte = evenement.getEventProperties().get(RoutingContext.class.getName());
        // Les autres mécanismes (clé MCP, en-tête remote user, session déjà
        // ouverte) produisent le même événement et ne concernent pas ce verrou.
        if (!(contexte instanceof RoutingContext routage)
                || !CHEMIN_CONNEXION.equals(routage.normalizedPath())) {
            return;
        }
        Instant maintenant = Instant.now();
        parAdresse.compute(adresse(routage), (ignore, courant) -> {
            if (courant == null || courant.dernier().plus(dureeBlocage).isBefore(maintenant)) {
                return new Echecs(1, maintenant);
            }
            return new Echecs(courant.nombre() + 1, maintenant);
        });
    }

    /**
     * Ce qu'une connexion réussie laisse derrière elle : l'identité établie sur
     * la requête, et le cookie de session dans la réponse. Un échec ne produit
     * ni l'un ni l'autre — c'est d'ailleurs ce que vérifie déjà
     * {@code AuthentificationAdminTest#unMauvaisMotDePasseEstRefuse}.
     */
    private boolean connexionReussie(RoutingContext contexte) {
        if (contexte.user() != null) {
            return true;
        }
        HttpServerResponse reponse = contexte.response();
        for (String entete : reponse.headers().getAll(HttpHeaders.SET_COOKIE)) {
            // Une valeur vide est une suppression de cookie, pas une session.
            if (entete.startsWith(nomCookieSession + "=") && !entete.startsWith(nomCookieSession + "=;")) {
                return true;
            }
        }
        return false;
    }

    /** Secondes restantes de blocage, {@code 0} si l'adresse peut tenter sa chance. */
    private long secondesDeBlocage(String adresse) {
        Echecs echecs = parAdresse.get(adresse);
        if (echecs == null || echecs.nombre() < maxEchecs) {
            return 0;
        }
        long restant = Duration.between(Instant.now(), echecs.dernier().plus(dureeBlocage)).toSeconds();
        if (restant <= 0) {
            parAdresse.remove(adresse);
            return 0;
        }
        return Math.max(restant, 1);
    }

    /**
     * L'adresse annoncée par le proxy est préférée à celle de la connexion :
     * derrière un reverse proxy, toutes les requêtes arrivent de la même
     * adresse, et compter là-dessus laisserait le premier attaquant venu
     * verrouiller la connexion de tout le monde. Cet en-tête n'est digne de
     * confiance que si l'origine n'est pas joignable sans passer par le proxy —
     * c'est la même condition que {@code proxy-address-forwarding}, et
     * {@code docs/securite.md} en fait un prérequis de déploiement.
     */
    private static String adresse(RoutingContext contexte) {
        String transmise = contexte.request().getHeader("X-Forwarded-For");
        if (transmise != null && !transmise.isBlank()) {
            return transmise.split(",")[0].trim();
        }
        return contexte.request().remoteAddress() == null
                ? "inconnue"
                : contexte.request().remoteAddress().hostAddress();
    }
}
