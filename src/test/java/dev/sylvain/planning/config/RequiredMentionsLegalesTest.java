package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A deployment in service must not publish an empty legal notice page.
 * {@code /api/mentions-legales} is public, the LCEN wants an identified
 * publisher and host, and the GDPR a legal basis, a retention period and an
 * address to exercise one's rights.
 */
class RequiredMentionsLegalesTest {

    private static final String EDITEUR = "Association Festival, 1 rue du Jeu, 53000 Laval";
    private static final String HEBERGEUR = "OVH SAS, 2 rue Kellermann, 59100 Roubaix";
    private static final String CONTACT = "contact@example.org";
    private static final String BASE_LEGALE = "Exécution du contrat de bénévolat";
    private static final String CONSERVATION = "Un an après la fin de l'édition";

    @Test
    void aFilledNoticeBoots() {
        assertThatCode(() ->
                        RequiredMentionsLegales.check(false, EDITEUR, HEBERGEUR, CONTACT, BASE_LEGALE, CONSERVATION))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptyNoticeRefusesTheBoot() {
        assertThatThrownBy(() -> RequiredMentionsLegales.check(false, "", "", "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGAL_EDITEUR");
    }

    /**
     * One message names every missing variable: an operator who fixes one per
     * boot pays five restarts to learn what a single message can say.
     */
    @Test
    void theMessageNamesEveryMissingVariableAtOnce() {
        assertThatThrownBy(() -> RequiredMentionsLegales.check(false, "", "", "", "", ""))
                .hasMessageContaining("LEGAL_EDITEUR")
                .hasMessageContaining("LEGAL_HEBERGEUR")
                .hasMessageContaining("LEGAL_CONTACT")
                .hasMessageContaining("LEGAL_BASE_LEGALE")
                .hasMessageContaining("LEGAL_CONSERVATION");
    }

    /** The message names the way out, or an operator reads it as a dead end. */
    @Test
    void theMessageNamesTheEscapeHatch() {
        assertThatThrownBy(() -> RequiredMentionsLegales.check(false, "", "", "", "", ""))
                .hasMessageContaining("LEGAL_DEMO_INSTANCE");
    }

    /** Blank is not filled in: the page renders the same "not provided" case. */
    @Test
    void aBlankValueCountsAsMissing() {
        assertThatThrownBy(
                        () -> RequiredMentionsLegales.check(false, EDITEUR, "   ", CONTACT, BASE_LEGALE, CONSERVATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGAL_HEBERGEUR")
                .hasMessageNotContaining("LEGAL_EDITEUR");
    }

    /** A demo instance says so explicitly, and an empty page is then the honest answer. */
    @Test
    void aDemoInstanceBootsWithAnEmptyNotice() {
        assertThatCode(() -> RequiredMentionsLegales.check(true, "", "", "", "", ""))
                .doesNotThrowAnyException();
    }
}
