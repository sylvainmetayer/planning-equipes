package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deferring somebody's message (issue #503): « on ne prévient pas Untel ce
 * soir, on l'appelle d'abord ».
 *
 * <p>What has to hold is the promise the feature makes — a deferred person is
 * <b>not</b> a person written off. The publication happens for everybody, so
 * the espace stays coherent, but their own reference does not move: the next
 * preview names them again, with the écart counted from what they really
 * received rather than from a plan they were never sent.</p>
 */
@QuarkusTest
class PublicationExclusionTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9401L;
    private static final String EMAIL_ALICE = "alice-excl@example.org";
    private static final String EMAIL_BRUNO = "bruno-excl@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        mailbox.clear();
        forgetPublications();
        persistence.clearDatabase();
        persistPlan("EXC-A", "EXC-B");
        donnerEmail("EXC-A", EMAIL_ALICE);
        donnerEmail("EXC-B", EMAIL_BRUNO);
    }

    @AfterEach
    void nettoyer() {
        forgetPublications();
    }

    /* ------------------------------- Nominal ------------------------------- */

    @Test
    void anExcludedPersonReceivesNothingAndStaysToBeTold() {
        publier();
        mailbox.clear();
        echangerLesDeuxSieges();

        JsonPath rapport = publier("EXC-B");

        assertThat(rapport.getInt("envoyes")).isEqualTo(1);
        assertThat(rapport.getList("differes")).containsExactly("Bruno Petit");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(EMAIL_BRUNO)).isEmpty();

        JsonPath apercu = apercu();
        assertThat(apercu.getInt("nombreConcernes")).isEqualTo(1);
        assertThat(apercu.getList("destinataires.nomAffiche")).containsExactly("Bruno Petit");
        assertThat(apercu.getList("destinataires.reporte")).containsExactly(true);
        assertThat(apercu.getList("destinataires[0].changements", String.class))
                .contains("samedi 11/07 : Stand excl un 14h-16h remplace Stand excl deux 14h-16h");
    }

    /**
     * The écart accumulates: somebody deferred twice reads, when their message
     * finally goes out, the difference with what they were last sent — not
     * with a plan that changed twice behind their back.
     */
    @Test
    void theGapAccumulatesUntilTheMessageFinallyGoesOut() {
        publier();
        echangerLesDeuxSieges();
        publier("EXC-B");
        // A third state: Bruno leaves the plan entirely.
        persistPlanSolo("EXC-A");
        publier("EXC-B");
        mailbox.clear();

        JsonPath apercu = apercu();
        assertThat(apercu.getList("destinataires.nomAffiche")).containsExactly("Bruno Petit");
        assertThat(apercu.getList("destinataires[0].changements", String.class))
                .containsExactly("samedi 11/07 : Stand excl deux 14h-16h (retiré)");

        publier();
        assertThat(mailbox.getMailsSentTo(EMAIL_BRUNO)).hasSize(1);
        assertThat(apercu().getInt("nombreConcernes")).isZero();
    }

    @Test
    void theTraceAndTheHistoryBothNameWhoWasLeftOut() {
        publier();
        echangerLesDeuxSieges();
        publier("EXC-B");

        JsonPath trace = given().when()
                .get("/api/planning/publication/destinataires")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<String> ids = trace.getList("animateurId", String.class);
        assertThat(trace.getList("statut", String.class).get(ids.indexOf("EXC-B")))
                .isEqualTo("EXCLU");

        JsonPath historique = given().when()
                .get("/api/historique?limite=50")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<String> actions = historique.getList("action", String.class);
        assertThat(actions).contains("PUBLICATION_DIFFEREE");
        assertThat(historique.getList("entiteId", String.class).get(actions.indexOf("PUBLICATION_DIFFEREE")))
                .isEqualTo("EXC-B");
    }

    /* -------------------------------- Limites ------------------------------ */

    @Test
    void excludingEverybodyIsRefusedRatherThanPublishingToNobody() {
        given().contentType(ContentType.JSON)
                .body(Map.of("exclusions", List.of("EXC-A", "EXC-B")))
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(409)
                .body("message", containsString("ne préviendrait personne"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    /** An id nobody is expecting is ignored: the screen may have been read before the last change. */
    @Test
    void anUnknownExclusionChangesNothing() {
        JsonPath rapport = publier("EXC-INCONNU");

        assertThat(rapport.getInt("envoyes")).isEqualTo(2);
        assertThat(rapport.getList("differes")).isEmpty();
    }

    /* ---------------------------------- CSV -------------------------------- */

    @Test
    void theReviewTableIsDownloadableAsOneLinePerPerson() {
        String csv = given().when()
                .get("/api/planning/publication/export")
                .then()
                .statusCode(200)
                .contentType("text/csv")
                .extract()
                .asString();

        String[] lignes = csv.split("\n");
        assertThat(lignes[0])
                .isEqualTo("﻿animateurId;nomAffiche;email;premiereDiffusion;reporte;ajouts;retraits;"
                        + "deplacements;mineur;confirmation;changements;demandes");
        assertThat(lignes).hasSize(3);
        assertThat(lignes[1]).startsWith("EXC-A;Alice Martin;" + EMAIL_ALICE + ";true;false;1;0;0;false;;");
    }

    /* -------------------------------- Helpers ------------------------------ */

    private JsonPath apercu() {
        return given().when()
                .get("/api/planning/publication")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private JsonPath publier(String... exclusions) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("exclusions", List.of(exclusions)))
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private void forgetPublications() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }

    private void persistPlan(String surStandUn, String surStandDeux) {
        Animateur alice = alice();
        Animateur bruno = bruno();
        Creneau creneau = creneau();
        PosteAffectation posteUn = new PosteAffectation("EXC-P1", standUn(), creneau);
        posteUn.setAnimateur("EXC-A".equals(surStandUn) ? alice : bruno);
        PosteAffectation posteDeux = new PosteAffectation("EXC-P2", standDeux(), creneau);
        posteDeux.setAnimateur("EXC-A".equals(surStandDeux) ? alice : bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));
    }

    /** Only the first stand is staffed: the other seat leaves the plan with its holder. */
    private void persistPlanSolo(String surStandUn) {
        Animateur alice = alice();
        Animateur bruno = bruno();
        PosteAffectation posteUn = new PosteAffectation("EXC-P1", standUn(), creneau());
        posteUn.setAnimateur("EXC-A".equals(surStandUn) ? alice : bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteUn)));
    }

    private void echangerLesDeuxSieges() {
        persistPlan("EXC-B", "EXC-A");
    }

    private static Animateur alice() {
        return new Animateur("EXC-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
    }

    private static Animateur bruno() {
        return new Animateur("EXC-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
    }

    private static Stand standUn() {
        return new Stand("EXC-S1", "Stand excl un", Set.of(), 1, 1, false);
    }

    private static Stand standDeux() {
        return new Stand("EXC-S2", "Stand excl deux", Set.of(), 1, 1, false);
    }

    private static Creneau creneau() {
        return new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }
}
