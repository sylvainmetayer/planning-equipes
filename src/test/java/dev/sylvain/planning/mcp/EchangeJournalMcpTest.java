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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A grouped tool that delegates to its former name must journal one line, the
 * one of the action it performed: ArC intercepts a tool called on {@code this}
 * like any other call, so the delegation goes through a private method.
 */
@QuarkusTest
class EchangeJournalMcpTest {

    @Inject
    EchangeMcpTools tools;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    JournalActionService journal;

    private String edition;

    @BeforeEach
    void createAnEdition() {
        edition = editions.create(new Edition(null, "Journal échange", false, null))
                .getId();
    }

    @AfterEach
    void dropTheEdition() {
        editions.delete(edition);
    }

    @Test
    void aRefusedSwapDecisionIsJournalledOnceUnderItsOwnAction() {
        assertThatThrownBy(() -> tools.decideDemandeEchange("inconnue", "refuser", null, edition))
                .isInstanceOf(RuntimeException.class);

        List<EntreeJournal> lignes = editionContext.executeIn(edition, () -> journal.list(10));
        assertThat(lignes).extracting(EntreeJournal::action).containsExactly("ECHANGE_REFUSE");
    }
}
