package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import dev.sylvain.planning.config.ConfigRemoteUser;

import org.junit.jupiter.api.Test;

/**
 * Plain unit test of the trust rules, without Quarkus: the header checks and,
 * above all, the startup refusal.
 *
 * <p>That refusal is the point of the class and cannot be covered by a
 * {@code @QuarkusTest} — asserting that an application does <em>not</em> boot
 * is exactly what a test harness that boots it first cannot express.</p>
 */
class RemoteUserAuthentificationTest {

    @Test
    void leModeDesactiveNeRegardeAucunEnTete() {
        RemoteUserAuthentification remote = configure(false, "secret", "chef@exemple.fr");

        assertThat(remote.emailDeConfiance(enTetes(Map.of(
                "Remote-Auth-Secret", "secret",
                "Remote-Email", "chef@exemple.fr")))).isEmpty();
    }

    @Test
    void leBonSecretRendLAdresseExploitable() {
        RemoteUserAuthentification remote = configure(true, "secret", "chef@exemple.fr");

        assertThat(remote.emailDeConfiance(enTetes(Map.of(
                "Remote-Auth-Secret", "secret",
                "Remote-Email", " Chef@Exemple.FR "))))
                .contains("chef@exemple.fr");
    }

    @Test
    void unSecretAbsentOuFauxRendLAdresseInexploitable() {
        RemoteUserAuthentification remote = configure(true, "secret", "chef@exemple.fr");

        assertThat(remote.emailDeConfiance(enTetes(Map.of("Remote-Email", "chef@exemple.fr")))).isEmpty();
        assertThat(remote.emailDeConfiance(enTetes(Map.of(
                "Remote-Auth-Secret", "presque",
                "Remote-Email", "chef@exemple.fr")))).isEmpty();
    }

    @Test
    void seuleLAdresseConfigureeEstAdministratrice() {
        RemoteUserAuthentification remote = configure(true, "secret", "chef@exemple.fr");

        assertThat(remote.estAdmin("CHEF@exemple.fr")).isTrue();
        assertThat(remote.estAdmin("quelquun@exemple.fr")).isFalse();
    }

    @Test
    void sansAdminEmailPersonneNEstAdministrateur() {
        RemoteUserAuthentification remote = configure(true, "secret", "");

        assertThat(remote.estAdmin("")).isFalse();
        assertThat(remote.estAdmin("chef@exemple.fr")).isFalse();
    }

    @Test
    void activerLeModeSansSecretFaitEchouerLeDemarrage() {
        RemoteUserAuthentification remote = configure(true, "", "chef@exemple.fr");

        assertThatThrownBy(() -> remote.verifierConfiguration(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning.auth.remote-user.secret");
    }

    private static RemoteUserAuthentification configure(boolean actif, String secret, String emailAdmin) {
        RemoteUserAuthentification remote = new RemoteUserAuthentification();
        remote.config = new ConfigRemoteUserFixe(actif, "Remote-Email", "Remote-Auth-Secret",
                Optional.of(secret), Optional.of(emailAdmin));
        return remote;
    }

    /**
     * The "trusted header" mode is read through
     * {@link dev.sylvain.planning.config.ConfigRemoteUser}, a
     * {@code @ConfigMapping} interface: a test provides it by implementing it,
     * rather than by writing into five fields.
     */
    private record ConfigRemoteUserFixe(boolean enabled, String header, String secretHeader,
            Optional<String> secret, Optional<String> adminEmail) implements ConfigRemoteUser {
    }

    private static java.util.function.Function<String, String> enTetes(Map<String, String> valeurs) {
        return valeurs::get;
    }
}
