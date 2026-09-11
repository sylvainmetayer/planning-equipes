package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The identity check of {@link AnimateurService}, without a container: what
 * is refused and how the refusal is worded. That {@code create} and
 * {@code update} both apply it is proved over HTTP by
 * {@code AnimateurResourceTest}.
 */
class AnimateurServiceTest {

    @Test
    void refusesAMissingBirthDateAndSaysWhyItMatters() {
        Animateur animateur = new Animateur("AS-1", "Alice", "Martin", null, false);

        assertThatThrownBy(() -> AnimateurService.requireIdentity(animateur))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage("Fiche incomplète : la date de naissance est obligatoire ; sans date de naissance, "
                        + "tout le régime mineur / majeur est indéterminé.");
    }

    @Test
    void treatsABlankNameAsMissing() {
        Animateur animateur = new Animateur("AS-2", "Alice", "  ", LocalDate.of(1990, 1, 1), false);

        assertThatThrownBy(() -> AnimateurService.requireIdentity(animateur))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage("Fiche incomplète : le nom est obligatoire.");
    }

    @Test
    void namesEveryMissingFieldInOneSentence() {
        Animateur animateur = new Animateur("AS-3", null, "", null, false);

        assertThatThrownBy(() -> AnimateurService.requireIdentity(animateur))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("le prénom, le nom et la date de naissance sont obligatoires");
    }

    @Test
    void aCompleteIdentityPassesTheCheck() {
        Animateur animateur = new Animateur("AS-4", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);

        assertThatCode(() -> AnimateurService.requireIdentity(animateur)).doesNotThrowAnyException();
    }
}
