package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A <b>public</b> deployment must not publish an empty legal notice page; any other only
 * warns (ADR 0110).
 * {@code /api/mentions-legales} is public, the LCEN wants an identified
 * publisher and host, and the GDPR a legal basis, a retention period and an
 * address to exercise one's rights.
 */
class RequiredMentionsLegalesTest {

    private static final String EDITEUR = "Association Ludique, 1 rue du Jeu, 53000 Laval";
    private static final String HEBERGEUR = "OVH SAS, 2 rue Kellermann, 59100 Roubaix";
    private static final String CONTACT = "contact@example.org";
    private static final String BASE_LEGALE = "Exécution du contrat de bénévolat";
    private static final String CONSERVATION = "Un an après la fin de l'édition";

    @Test
    void aFilledNoticeBoots() {
        assertThatCode(() -> RequiredMentionsLegales.check(
                        false, true, EDITEUR, HEBERGEUR, CONTACT, BASE_LEGALE, CONSERVATION))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptyNoticeRefusesTheBoot() {
        assertThatThrownBy(() -> RequiredMentionsLegales.check(false, true, "", "", "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("editeur");
    }

    /**
     * One message names every missing variable: an operator who fixes one per
     * boot pays five restarts to learn what a single message can say.
     */
    @Test
    void theMessageNamesEveryMissingVariableAtOnce() {
        assertThatThrownBy(() -> RequiredMentionsLegales.check(false, true, "", "", "", "", ""))
                .hasMessageContaining("editeur")
                .hasMessageContaining("hebergeur")
                .hasMessageContaining("contact")
                .hasMessageContaining("base-legale")
                .hasMessageContaining("conservation");
    }

    /** The message names the way out, or an operator reads it as a dead end. */
    @Test
    void theMessageNamesTheEscapeHatch() {
        assertThatThrownBy(() -> RequiredMentionsLegales.check(false, true, "", "", "", "", ""))
                .hasMessageContaining("LEGAL_DEMO_INSTANCE");
    }

    /** Blank is not filled in: the page renders the same "not provided" case. */
    @Test
    void aBlankValueCountsAsMissing() {
        assertThatThrownBy(() ->
                        RequiredMentionsLegales.check(false, true, EDITEUR, "   ", CONTACT, BASE_LEGALE, CONSERVATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hebergeur")
                .hasMessageNotContaining("editeur");
    }

    /** A demo instance says so explicitly, and an empty page is then the honest answer. */
    @Test
    void aDemoInstanceBootsWithAnEmptyNotice() {
        assertThatCode(() -> RequiredMentionsLegales.check(true, true, "", "", "", "", ""))
                .doesNotThrowAnyException();
    }

    /** Only a public instance is refused: elsewhere the operator is warned, and the boot goes on. */
    @Test
    void anInstanceThatIsNotPublicBootsWithAnEmptyNotice() {
        assertThatCode(() -> RequiredMentionsLegales.check(false, false, "", "", "", "", ""))
                .doesNotThrowAnyException();
    }

    /**
     * A 3.x public instance that does not set {@code PUBLIC_INSTANCE} stopped
     * refusing this boot in 4.0: the warning says so and names the variable.
     */
    @Test
    void anUndeclaredInstanceIsToldItIsNoLongerTreatedAsPublic() {
        assertThat(RequiredMentionsLegales.check(false, null, "", "", "", "", ""))
                .contains("editeur")
                .contains("n'est plus traitée comme publique")
                .contains("PUBLIC_INSTANCE=true");
    }

    /** {@code PUBLIC_INSTANCE=false} is a decision: the 4.0 sentence is not repeated to it. */
    @Test
    void anInstanceDeclaredNotPublicIsNotToldAboutThe40Break() {
        assertThat(RequiredMentionsLegales.check(false, false, "", "", "", "", ""))
                .contains("editeur")
                .doesNotContain("n'est plus traitée comme publique");
        assertThat(RequiredMentionsLegales.check(false, null, EDITEUR, HEBERGEUR, CONTACT, BASE_LEGALE, CONSERVATION))
                .isNull();
    }
}
