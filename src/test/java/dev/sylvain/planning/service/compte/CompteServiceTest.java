package dev.sylvain.planning.service.compte;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.JournalActionService;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompteServiceTest {

    @Test
    void anAccountCannotDeactivateItself() {
        Compte moi = new Compte("c-1", "moi@example.org", null, null, null, null, null, List.of());
        CompteService service = new CompteService();
        service.journal = new JournalActionService(
                null,
                QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal("moi@example.org"))
                        .addAttribute(Compte.IDENTITY_ATTRIBUTE, moi)
                        .build(),
                Duration.ofDays(90));

        assertThatThrownBy(() -> service.deactivate("c-1")).isInstanceOf(BusinessError.Conflict.class);
    }

    @Test
    void anAddressIsBoundedAndMustLookLikeOne() {
        assertThatThrownBy(() -> CompteService.normalize("a".repeat(320) + "@example.org"))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> CompteService.normalize("pas-une-adresse")).isInstanceOf(BusinessError.Invalid.class);
    }
}
