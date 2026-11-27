package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.PublicationMcpTools.RapportRelanceView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reminder over MCP: the tool and its 3.x name share one body, so the
 * former name still sends the mail it used to — once.
 */
@QuarkusTest
class RelanceMcpToolsTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final String EMAIL = "relance-mcp@example.org";

    @Inject
    PublicationMcpTools publicationTools;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        forgetPublications();
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM confirmation_planning");
        Animateur alice = new Animateur("RMC-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Stand stand = new Stand("RMC-S1", "Stand relance MCP", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(9501L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation poste = new PosteAffectation("RMC-P1", stand, creneau);
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice), List.of(poste)));

        Animateur fiche = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals("RMC-A"))
                .findFirst()
                .orElseThrow();
        fiche.setEmail(EMAIL);
        referenceData.updateAnimateur("RMC-A", fiche);
        try {
            publication.publier();
        } catch (BusinessError.Conflict _) {
            // Nothing new to announce: the plan on display is already the published one.
        }
        mailbox.clear();
    }

    /** A published snapshot survives {@code clearDatabase()}; the suite shares one database. */
    @AfterEach
    void cleanUp() {
        forgetPublications();
    }

    @Test
    void theFormerNameStillRemindsOnce() {
        RapportRelanceView rapport = publicationTools.remindAnimateurs(List.of("RMC-A"), "E1");

        assertThat(rapport.envoyes()).containsExactly("RMC-A");
        assertThat(mailbox.getMailsSentTo(EMAIL)).hasSize(1);
    }

    private void forgetPublications() {
        execute("DELETE FROM version_plan");
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
    }
}
