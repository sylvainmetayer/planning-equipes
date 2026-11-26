package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.journal.EntreeJournal;
import dev.sylvain.planning.service.journal.JournalActionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code calcul} section of {@code modifier_parametres}: the edition's
 * end-of-solve mail is the only thing it writes, and it answers with the
 * instance's solve budget the operator set (ADR 0110), which no argument changes.
 */
@QuarkusTest
class ParametresSolveurMcpToolsTest {

    @Inject
    ParametresMcpTools tools;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    JournalActionService journal;

    private String edition;

    @BeforeEach
    void createAnEdition() {
        edition = editions.create(new Edition(null, "Section calcul", false, null))
                .getId();
    }

    @AfterEach
    void dropTheEdition() {
        editions.delete(edition);
    }

    @Test
    void theMailSwitchIsWrittenAndTheInstanceBudgetAnswered() {
        var view = calcul(true);

        assertThat(view.mailFinResolution()).isTrue();
        assertThat(view.instance().defaultSecondsLimit()).isPositive();
        assertThat(calcul(null).mailFinResolution()).as("kept when not given").isTrue();
    }

    /** One refusal per section: a stamp read before another write is a 409, and no stamp checks nothing. */
    @Test
    void aStaleStampIsRefusedOnEachSectionAndAnAbsentOneChecksNothing() {
        String stale = "2020-01-01T00:00:00Z";
        for (String section : new String[] {"legal", "organisation", "notifications", "calcul"}) {
            // The first write creates the row; the stamp it carries is the one a reader would send back.
            call(section, section.equals("legal") ? 40 : null, section.equals("calcul") ? true : null);
            assertThatThrownBy(() -> call(
                            section,
                            section.equals("legal") ? 41 : null,
                            section.equals("calcul") ? false : null,
                            stale))
                    .as(section)
                    .isInstanceOf(io.quarkiverse.mcp.server.ToolCallException.class)
                    .hasMessageContaining("modifiée");
            // Nothing checked: the write lands.
            call(section, section.equals("legal") ? 42 : null, section.equals("calcul") ? false : null);
        }
        var current = (ParametresMcpTools.ParametresLegauxView)
                tools.getParametres("legal", edition).parametres();
        assertThat(current.dureePauseMinutes()).isEqualTo(42);
        assertThat(current.modifieLe()).isNotNull();
        // The stamp just read is the precondition that passes.
        call("legal", 43, null, current.modifieLe().toString());
    }

    @Test
    void theJournalNamesTheSectionAsItsRestRouteDoes() {
        calcul(true);

        EntreeJournal ligne =
                editionContext.executeIn(edition, () -> journal.list(5)).getFirst();
        assertThat(ligne.acteur()).hasToString("ASSISTANT");
        assertThat(ligne.action()).isEqualTo("PARAMETRES_SOLVEUR_MODIFIES");
    }

    @Test
    void aSectionIsReadByItsName() {
        var view = tools.getParametres("calcul", edition);

        assertThat(view.section()).isEqualTo("calcul");
        assertThat(view.parametres()).isInstanceOf(ParametresMcpTools.ParametresSolveurView.class);
    }

    @Test
    void anUnknownSectionAndAnArgumentOfAnotherSectionAreRefused() {
        assertThatThrownBy(() -> tools.getParametres("solveur", edition)).hasMessageContaining("Section inconnue");
        assertThatThrownBy(() -> tools.getParametres("instance", edition)).hasMessageContaining("Section inconnue");
        // dureePauseMinutes is a legal field, not a calcul one.
        assertThatThrownBy(() -> call("calcul", 25, null)).hasMessageContaining("n'est pas de la section");
    }

    /** {@code modifier_parametres} on the calcul section, as the tool is called. */
    private ParametresMcpTools.ParametresSolveurView calcul(Boolean mailFinResolution) {
        return (ParametresMcpTools.ParametresSolveurView)
                call("calcul", null, mailFinResolution).parametres();
    }

    /** The positional call: {@code dureePauseMinutes} is the 6th argument, the mail switch the 23rd. */
    private ParametresMcpTools.ParametresSectionView call(
            String section, Integer dureePauseMinutes, Boolean mailFinResolution) {
        return call(section, dureePauseMinutes, mailFinResolution, null);
    }

    private ParametresMcpTools.ParametresSectionView call(
            String section, Integer dureePauseMinutes, Boolean mailFinResolution, String modifieLe) {
        return tools.updateParametres(
                section,
                null,
                null,
                null,
                null,
                dureePauseMinutes,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mailFinResolution,
                null,
                null,
                null,
                modifieLe,
                edition);
    }
}
