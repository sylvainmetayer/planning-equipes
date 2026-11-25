package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.config.ConfigSecours;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.journal.LoginJournalEntry;
import dev.sylvain.planning.service.journal.LoginJournalEntry.Evenement;
import dev.sylvain.planning.service.journal.LoginJournalService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * Closes {@code /j_security_check} while the break-glass door is closed
 * (ADR 0093: behind an OIDC provider, unless {@code ADMIN_LOCAL_SECOURS=true}).
 *
 * <p>Closed, the form login and its embedded {@code admin} account stay wired
 * underneath: a {@code POST /j_security_check} carrying {@code ADMIN_PASSWORD}
 * would otherwise open a session with the {@code admin} role — without the
 * provider and without a named person. A door the login page merely stops
 * offering is still a door, and the weaker one.</p>
 *
 * <p><b>Why a filter and not a permission.</b> {@code quarkus.http.auth.permission.*}
 * is checked after authentication, and the form mechanism completes the login
 * and sets its cookie from inside that phase, so a {@code deny} policy would
 * arrive too late. {@code quarkus.http.auth.form.enabled} is fixed at build
 * time. This filter runs where {@link AdminLoginLimiter} already answers
 * {@code 429}: before anything handles the request.</p>
 *
 * <p>{@code 409} rather than {@code 401}, which would read as a wrong password
 * and send an operator hunting for a credential rather than reading their
 * configuration.</p>
 *
 * <p>It also writes the one line of the admin login journal that says the door
 * was <b>left open</b> at startup behind a provider, so that an open
 * break-glass door is a trace and not a silent default.</p>
 */
@ApplicationScoped
public class FormLoginSecours {

    /** Just above {@link AdminLoginLimiter}: a closed door has no attempts to count. */
    private static final int PRIORITE = 260;

    private final ConfigSecours secours;
    private final ConfigOidc oidc;
    private final LoginJournalService journal;
    private final CompteService comptes;
    private final AdminLoginLimiter limiter;

    @Inject
    public FormLoginSecours(
            ConfigSecours secours,
            ConfigOidc oidc,
            LoginJournalService journal,
            CompteService comptes,
            AdminLoginLimiter limiter) {
        this.secours = secours;
        this.oidc = oidc;
        this.journal = journal;
        this.comptes = comptes;
        this.limiter = limiter;
    }

    public void register(@Observes Filters filtres) {
        filtres.register(this::apply, PRIORITE);
    }

    void journalOuverture(@Observes StartupEvent demarrage) {
        if (!oidc.enabled()) {
            return;
        }
        if (!secours.open(true) && !hasActiveAdministrator()) {
            Log.warn("Aucun compte administrateur actif et le compte de secours est fermé : personne ne peut "
                    + "administrer cette instance. Rouvrir le secours (ADMIN_LOCAL_SECOURS=true), créer son "
                    + "compte et lui accorder le droit ADMIN (docs/exploitation.md).");
        }
        if (secours.open(true)) {
            Log.warn("Le compte de secours est OUVERT (ADMIN_LOCAL_SECOURS=true) alors qu'un fournisseur OIDC "
                    + "est branché : un mot de passe unique ouvre l'administration. Le refermer dès que "
                    + "l'incident est passé.");
            journal.record(List.of(new LoginJournalEntry(0, Instant.now(), Evenement.SECOURS_OUVERT, "demarrage")));
        }
    }

    private boolean hasActiveAdministrator() {
        try {
            Instant now = Instant.now();
            return comptes.list().stream()
                    .anyMatch(c -> CompteService.isActive(c)
                            && CompteService.roles(c, now).contains("admin"));
        } catch (RuntimeException e) {
            return true; // not provable at startup: say nothing rather than raise a false alarm
        }
    }

    private void apply(RoutingContext contexte) {
        if (secours.open(oidc.enabled()) || !AdminLoginLimiter.isAdminFormPath(contexte.normalizedPath())) {
            contexte.next();
            return;
        }
        limiter.recordClosedDoorAttempt(contexte);
        contexte.response()
                .setStatusCode(409)
                .putHeader("Content-Type", "application/json")
                .end("{\"message\":\"La connexion par mot de passe est fermée : ce déploiement authentifie par"
                        + " son fournisseur d'identité. Le compte de secours ne s'ouvre qu'avec"
                        + " ADMIN_LOCAL_SECOURS=true.\"}");
    }
}
