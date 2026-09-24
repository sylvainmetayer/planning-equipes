package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The deny-by-default layer of ADR 0049, read off {@code application.properties}
 * rather than trusted to review: a public route added "for the time being" and
 * a role that grants more than it names are the two ways this kind of model
 * rots, and neither shows in a diff of the route itself.
 */
class PolitiquesHttpStructuralTest {

    private static final Path PROPRIETES = Path.of("src/main/resources/application.properties");

    /**
     * Every {@code permit} policy of the production profile, with the paths it
     * opens. A new entry needs its reason in {@code application.properties}
     * and in {@code docs/securite.md}, then a line here.
     */
    private static final Map<String, String> PERMIS = Map.of(
            "espace-animateur", "/api/espace-animateur/*",
            "abonnement-ics", "/api/abonnements/*",
            "affichage-mural", "/api/mural/*",
            "auth-public", "/api/auth/me,/api/auth/logout,/api/config,/api/branding,/api/mentions-legales",
            "health", "/q/health,/q/health/*");

    @Test
    void lesSeulesRoutesOuvertesSontCellesQuiSontArgumentees() throws IOException {
        Properties proprietes = read();
        Map<String, String> ouvertes = new TreeMap<>();
        for (String cle : proprietes.stringPropertyNames()) {
            if (cle.startsWith("quarkus.http.auth.permission.")
                    && cle.endsWith(".policy")
                    && "permit".equals(proprietes.getProperty(cle))) {
                String nom = cle.substring("quarkus.http.auth.permission.".length(), cle.length() - ".policy".length());
                ouvertes.put(nom, proprietes.getProperty("quarkus.http.auth.permission." + nom + ".paths"));
            }
        }
        assertThat(ouvertes).isEqualTo(new TreeMap<>(PERMIS));
    }

    /** {@code /api} is the admin role's, named — not "anyone who signed in". */
    @Test
    void lApiExigeLeRoleAdmin() throws IOException {
        Properties proprietes = read();
        assertThat(proprietes.getProperty("quarkus.http.auth.permission.admin-api.paths"))
                .isEqualTo("/api/*");
        assertThat(proprietes.getProperty("quarkus.http.auth.permission.admin-api.policy"))
                .isEqualTo("role-admin");
        assertThat(proprietes.getProperty("quarkus.http.auth.policy.role-admin.roles-allowed"))
                .isEqualTo("admin");
        assertThat(proprietes.getProperty("quarkus.http.auth.permission.api-docs.policy"))
                .isEqualTo("role-admin");
    }

    /**
     * {@code user} is the role Keycloak hands every account. A policy or the
     * espace built on it would open to every future account without anyone
     * granting it — the next role added to the realm would arrive with that
     * access.
     */
    @Test
    void leRoleOrdinaireUserNOuvreRien() throws IOException {
        Properties proprietes = read();
        for (String cle : proprietes.stringPropertyNames()) {
            if (cle.endsWith(".roles-allowed")) {
                assertThat(Set.of(proprietes.getProperty(cle).split("\\s*,\\s*")))
                        .as(cle)
                        .doesNotContain("user");
            }
        }
        assertThat(proprietes.getProperty("planning.auth.oidc.animateur-role"))
                .doesNotContain(":user}")
                .isNotEqualTo("user");
    }

    /** Production ships Keycloak on and the break-glass door shut. */
    @Test
    void laProductionDemarreSurKeycloakPorteDeSecoursFermee() throws IOException {
        Properties proprietes = read();
        assertThat(proprietes.getProperty("planning.auth.oidc.enabled")).isEqualTo("${OIDC_ENABLED:true}");
        assertThat(proprietes.getProperty("planning.auth.secours.enabled")).isEqualTo("${ADMIN_SECOURS_ENABLED:false}");
    }

    /** The production profile only: {@code %dev.} and {@code %test.} keys are left out. */
    private static Properties read() throws IOException {
        Properties toutes = new Properties();
        try (Reader lecteur = Files.newBufferedReader(PROPRIETES)) {
            toutes.load(lecteur);
        }
        Properties production = new Properties();
        toutes.stringPropertyNames().stream()
                .filter(cle -> !cle.startsWith("%"))
                .forEach(cle -> production.setProperty(cle, toutes.getProperty(cle)));
        return production;
    }
}
