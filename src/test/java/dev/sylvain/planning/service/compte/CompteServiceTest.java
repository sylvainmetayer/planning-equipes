package dev.sylvain.planning.service.compte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.JournalActionService;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompteServiceTest {

    @Test
    void anAccountCannotDeactivateItself() {
        Compte moi = new Compte("c-1", "moi@example.org", null, null, null, null, null, List.of(), false);
        CompteService service = new CompteService(
                null,
                null,
                null,
                null,
                new JournalActionService(
                        null,
                        QuarkusSecurityIdentity.builder()
                                .setPrincipal(new QuarkusPrincipal("moi@example.org"))
                                .addAttribute(Compte.IDENTITY_ATTRIBUTE, moi)
                                .build(),
                        Duration.ofDays(90)));

        assertThatThrownBy(() -> service.deactivate("c-1")).isInstanceOf(BusinessError.Conflict.class);
    }

    @Test
    void anAddressIsBoundedAndMustLookLikeOne() {
        assertThatThrownBy(() -> CompteService.normalize("a".repeat(320) + "@example.org"))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> CompteService.normalize("pas-une-adresse")).isInstanceOf(BusinessError.Invalid.class);
    }

    /** A lecteur right widens what is seen, never what is written (ADR 0099). */
    @Test
    void aLecteurRightWidensTheScopeOfReadsOnly() {
        Instant now = Instant.now();
        Compte compte = new Compte(
                "c-2",
                "lecteur@example.org",
                null,
                null,
                null,
                null,
                null,
                List.of(
                        droit(RoleHabilitation.LECTEUR, "E1"),
                        droit(RoleHabilitation.ORGANISATEUR, "E2"),
                        droit(RoleHabilitation.RH, "E3")),
                false);

        assertThat(CompteService.isLecteur(compte, now)).isTrue();
        assertThat(CompteService.editionScope(compte, now).test("E1")).isTrue();
        assertThat(CompteService.editionScope(compte, now).test("E2")).isTrue();
        assertThat(CompteService.editionScope(compte, now).test("E3")).isFalse();
        assertThat(CompteService.writeScope(compte, now).test("E1")).isFalse();
        assertThat(CompteService.writeScope(compte, now).test("E2")).isTrue();
        assertThat(CompteService.writeScope(compte, now).test(null)).isFalse();
    }

    private static Habilitation droit(RoleHabilitation role, String edition) {
        return new Habilitation("h-" + role + edition, role, edition, null, null, Instant.EPOCH, null, null, List.of());
    }
}
