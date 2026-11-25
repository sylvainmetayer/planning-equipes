package dev.sylvain.planning.service.compte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import org.junit.jupiter.api.Test;

class LocalPasswordTest {

    @Test
    void douzeCaracteresSuffisentSansRegleDeComposition() {
        LocalPassword.check("aaaaaaaaaaaa");
        LocalPassword.check("élève très sûr");
    }

    @Test
    void onRefuseLeTropCourtEtLeTropLongPourBcrypt() {
        assertThatThrownBy(() -> LocalPassword.check("onze chars")).isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> LocalPassword.check(null)).isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> LocalPassword.check("a".repeat(73))).isInstanceOf(BusinessError.Invalid.class);
        LocalPassword.check("a".repeat(72));
    }

    @Test
    void leHachageSeVerifieEtNeContientPasLeMotDePasse() {
        String hash = LocalPassword.hash(dev.sylvain.planning.TestPasswords.LONG);
        assertThat(hash).startsWith("$2").doesNotContain("assez long");
        assertThat(LocalPassword.matches(dev.sylvain.planning.TestPasswords.LONG, hash))
                .isTrue();
        assertThat(LocalPassword.matches("un mot de passe assez lonG", hash)).isFalse();
    }

    @Test
    void sansHachageRienNePasseMaisLeCoutEstPaye() {
        assertThat(LocalPassword.matches(dev.sylvain.planning.TestPasswords.LONG, null))
                .isFalse();
        assertThat(LocalPassword.matches(null, null)).isFalse();
    }
}
