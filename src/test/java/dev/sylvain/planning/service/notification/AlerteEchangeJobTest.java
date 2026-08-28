package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.AlerteService;
import dev.sylvain.planning.service.DemandeEchangeService;
import dev.sylvain.planning.service.DemandeEchangeService.NouvelleDemande;
import dev.sylvain.planning.service.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Swap requests left to rot (issue #300).
 *
 * <p>Two behaviours are worth a test, and the second one is the whole reason
 * the feature is not simply a query: the alert fires once and <b>never comes
 * back</b>, however many nights the job runs afterwards. An alert repeated
 * every morning is one an organiser filters out of sight within a week.</p>
 */
@QuarkusTest
class AlerteEchangeJobTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    AlerteEchangeJob job;

    @Inject
    AlerteService alerteService;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        mailbox.clear();
        execute("DELETE FROM notification_planifiee");
        persistence.clearDatabase();
        persistPlan();
    }

    @Test
    void aRequestWaitingLongerThanTheThresholdIsAlerted() {
        submitAndAccept();
        ageBy(10);

        assertThat(job.run(threeDayThreshold(), Instant.now())).isEqualTo(1);

        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "ALERTE_ECHANGE".equals(alerte.type()))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.libelle()).contains("10 jours");
                    // Counted, never named: the Échanges screen is where the
                    // people are (docs/rgpd.md).
                    assertThat(alerte.animateurId()).isNull();
                });
    }

    /** The property that makes the alert readable: it is raised exactly once. */
    @Test
    void theSameRequestIsNeverAlertedTwice() {
        submitAndAccept();
        ageBy(10);

        assertThat(job.run(threeDayThreshold(), Instant.now())).isEqualTo(1);
        assertThat(job.run(threeDayThreshold(), Instant.now())).isZero();
        // A whole day later the demande is older still, and stays silent.
        assertThat(job.run(threeDayThreshold(), Instant.now().plusSeconds(86400))).isZero();

        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "ALERTE_ECHANGE".equals(alerte.type()))
                .hasSize(1);
    }

    @Test
    void aFreshRequestIsLeftAlone() {
        submitAndAccept();

        assertThat(job.run(threeDayThreshold(), Instant.now())).isZero();
        assertThat(alerteService.alertes(null)).isEmpty();
    }

    /**
     * A demande still waiting for the colleague's agreement is not waiting on
     * the organisation: alerting would send somebody to a row they cannot act
     * on.
     */
    @Test
    void aRequestStillAwaitingTheColleagueIsNotTheAdminsToDecide() {
        submitDemande();
        ageBy(10);

        assertThat(job.run(threeDayThreshold(), Instant.now())).isZero();
        assertThat(alerteService.alertes(null)).isEmpty();
    }

    /* -------------------------------- Helpers ------------------------------ */

    private static ParametresNotifications threeDayThreshold() {
        return new ParametresNotifications(true, LocalTime.of(18, 0), 72, 3);
    }

    private DemandeEchange submitDemande() {
        return demandeEchangeService.submit("ECH-A",
                List.of(new NouvelleDemande(9801L, "ECH-S1", "ECH-B", "Empêchement", null, null))).get(0);
    }

    private void submitAndAccept() {
        DemandeEchange demande = submitDemande();
        demandeEchangeService.acceptByTarget("ECH-B", demande.getId());
        mailbox.clear();
    }

    /** Pushes both timestamps back, so the demande looks its age to the job. */
    private void ageBy(int jours) {
        execute("UPDATE demande_echange SET cree_le = cree_le - INTERVAL '" + jours + " days',"
                + " cible_decide_le = cible_decide_le - INTERVAL '" + jours + " days'");
    }

    private void persistPlan() {
        Animateur alice = new Animateur("ECH-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ECH-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("ECH-S1", "Stand echange un", Set.of(), 1, 2, false);
        Creneau creneau = new Creneau(9801L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));

        PosteAffectation posteAlice = new PosteAffectation("ECH-P1", stand, creneau);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("ECH-P2", stand, creneau);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno),
                List.of(posteAlice, posteBruno)));
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
