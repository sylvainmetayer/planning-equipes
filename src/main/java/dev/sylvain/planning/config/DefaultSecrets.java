package dev.sylvain.planning.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to boot a production deployment still running on the passwords
 * shipped with the source.
 *
 * <p>{@code ADMIN_PASSWORD} defaults to {@code admin} and {@code DB_PASSWORD}
 * to {@code festival} (in dev, to the password of the development compose
 * stack), which is what makes {@code quarkus:dev} and the test suite work with
 * no setup. Neither default is scoped to a profile, so an image
 * started plainly — {@code docker run}, a Kubernetes manifest, anything that is
 * not the project's own {@code docker-compose.prod.yml}, which does demand both
 * — came up in production with {@code admin}/{@code admin} on an account that
 * opens the personal data of ~150 people, minors included. Nothing said so, in
 * the logs or anywhere else.</p>
 *
 * <p>Dropping the defaults instead would have been the obvious fix and is the
 * wrong one: they are what a contributor's first {@code quarkus:dev} relies on,
 * and the failure it would produce in production names a config expression, not
 * the variable an operator has to set.</p>
 *
 * <p>Read as a boot check rather than a {@code @ConfigMapping}, unlike the rest
 * of this package: both keys are Quarkus settings, and what matters about them
 * is not their value but that somebody chose it.</p>
 */
@ApplicationScoped
public class DefaultSecrets {

    /** The value {@code application.properties} ships for {@code ADMIN_PASSWORD}. */
    static final String SHIPPED_ADMIN_PASSWORD = "admin";

    /** The value {@code application.properties} ships for {@code DB_PASSWORD}. */
    static final String SHIPPED_DATABASE_PASSWORD = "festival";

    /**
     * {@link Optional} on both, and not for the usual blank-value reason: under
     * Dev Services the datasource password is handed out at runtime and the key
     * is simply absent from the configuration, which a {@code String} injection
     * point would turn into a startup failure for the whole test suite.
     */
    @ConfigProperty(name = "quarkus.security.users.embedded.users.admin")
    Optional<String> adminPassword;

    @ConfigProperty(name = "quarkus.datasource.password")
    Optional<String> databasePassword;

    void checkAtStartup(@Observes StartupEvent startup) {
        // Only a real deployment: dev and test are precisely the two modes the
        // defaults exist for.
        if (LaunchMode.current().isProduction()) {
            check(adminPassword.orElse(null), databasePassword.orElse(null));
        }
    }

    /**
     * Package-private and static so the rule can be unit-tested without a
     * production boot, like {@code BackupConfiguration.checkRetention}.
     */
    static void check(String adminPassword, String databasePassword) {
        refuse(
                "ADMIN_PASSWORD",
                adminPassword,
                SHIPPED_ADMIN_PASSWORD,
                "le compte admin démarrerait avec le mot de passe d'exemple");
        refuse(
                "DB_PASSWORD",
                databasePassword,
                SHIPPED_DATABASE_PASSWORD,
                "la base démarrerait avec le mot de passe d'exemple");
    }

    /**
     * Refuses the shipped value <b>and</b> the empty string.
     *
     * <p>An empty value is not "nothing chosen" by accident: it is an empty
     * password on the single account, which is worse than the default. And the
     * message names the variable and what it holds, rather than "it is not
     * set" — an operator who did set it to {@code admin} would otherwise read
     * the description of a problem that is not theirs.</p>
     */
    private static void refuse(String variable, String valeur, String exemple, String consequence) {
        if (valeur == null) {
            return;
        }
        if (valeur.isBlank()) {
            throw new IllegalStateException(
                    variable + " est vide : " + consequence + ". Donnez-lui une valeur avant de déployer.");
        }
        if (exemple.equals(valeur)) {
            throw new IllegalStateException(variable + " vaut encore la valeur d'exemple livrée avec les sources : "
                    + consequence + ". Donnez-lui une valeur propre à ce déploiement.");
        }
    }
}
