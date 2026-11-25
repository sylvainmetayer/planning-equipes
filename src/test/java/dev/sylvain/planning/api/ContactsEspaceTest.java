package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The animateur reads and writes their own contacts in their espace — and the number
 * they gave appears nowhere else a person or a tool could read it: not in a colleague's
 * espace, not in the fiches, not in the scenario file.
 */
@QuarkusTest
class ContactsEspaceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 13);
    private static final String EMAIL_ALICE = "contacts-alice@example.org";
    private static final String EMAIL_BRUNO = "contacts-bruno@example.org";

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

    private String tokenAlice;
    private String sessionAlice;
    private String sessionBruno;
    private String tokenBruno;

    @BeforeEach
    void seed() {
        RestAssured.requestSpecification = null;
        persistence.clearDatabase();
        Animateur alice = new Animateur("CT-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("CT-B", "Bruno", "Petit", LocalDate.of(2011, 2, 2), false);
        Stand stand = new Stand("CT-S1", "Stand contacts", Set.of(), 1, 2, false);
        Creneau matin = new Creneau(9801L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        PosteAffectation p1 = new PosteAffectation("CT-P1", stand, matin);
        p1.setAnimateur(alice);
        PosteAffectation p2 = new PosteAffectation("CT-P2", stand, matin);
        p2.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(p1, p2)));
        for (String[] who : new String[][] {{"CT-A", EMAIL_ALICE}, {"CT-B", EMAIL_BRUNO}}) {
            Animateur fiche = referenceData.findAnimateur(who[0]).orElseThrow();
            fiche.setEmail(who[1]);
            referenceData.updateAnimateur(who[0], fiche);
        }
        PlansPublies.publier(publication);
        tokenAlice = referenceData.findAnimateur("CT-A").orElseThrow().getAccessToken();
        tokenBruno = referenceData.findAnimateur("CT-B").orElseThrow().getAccessToken();
        mailbox.clear();
        sessionAlice = EspaceSessions.open(mailbox, tokenAlice, EMAIL_ALICE);
        mailbox.clear();
        sessionBruno = EspaceSessions.open(mailbox, tokenBruno, EMAIL_BRUNO);
        mailbox.clear();
    }

    @AfterEach
    void cleanUp() throws Exception {
        RestAssured.requestSpecification = null;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM animateur_contact");
            statement.executeUpdate("DELETE FROM plan_snapshot");
        }
    }

    private static String phone() {
        return "+" + (33_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    private io.restassured.specification.RequestSpecification as(String session) {
        return given().header("X-Edition-Id", "E1")
                .cookie("planning-espace", session)
                .contentType(ContentType.JSON);
    }

    @Test
    void anAnimateurWritesAndReadsOnlyTheirOwnContacts() {
        String urgence = phone();
        as(sessionAlice)
                .body(Map.of("urgenceNom", "Camille", "urgenceTelephone", urgence))
                .put("/api/espace-animateur/" + tokenAlice + "/contacts")
                .then()
                .statusCode(200)
                .body("contacts.urgenceTelephone", equalTo(urgence))
                .body("avertissements", hasSize(0));
        as(sessionAlice)
                .get("/api/espace-animateur/" + tokenAlice + "/contacts")
                .then()
                .statusCode(200)
                .body("urgenceNom", equalTo("Camille"));
        // Bruno's own contacts are his: nothing of Alice's.
        as(sessionBruno)
                .get("/api/espace-animateur/" + tokenBruno + "/contacts")
                .then()
                .statusCode(200)
                .body("urgenceNom", org.hamcrest.Matchers.nullValue());
        // Alice's session does not open Bruno's token.
        as(sessionAlice)
                .get("/api/espace-animateur/" + tokenBruno + "/contacts")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(401), equalTo(403)));
    }

    @Test
    void aMinorIsWarnedWhenTheirRepresentativeIsMissing() {
        as(sessionBruno)
                .body(Map.of("urgenceNom", "Camille"))
                .put("/api/espace-animateur/" + tokenBruno + "/contacts")
                .then()
                .statusCode(200)
                .body("avertissements[0].type", equalTo("MINEUR_SANS_REPRESENTANT"));
    }

    @Test
    void theNumberLeavesThroughNoOtherDoor() {
        String urgence = phone();
        String nom = "Zephyrine" + ThreadLocalRandom.current().nextInt(100_000);
        as(sessionAlice)
                .body(Map.of("urgenceNom", nom, "urgenceTelephone", urgence))
                .put("/api/espace-animateur/" + tokenAlice + "/contacts")
                .then()
                .statusCode(200);
        List<String> doors = List.of(
                as(sessionAlice).get("/api/espace-animateur/" + tokenAlice).asString(),
                as(sessionAlice)
                        .get("/api/espace-animateur/" + tokenAlice + "/collegues/CT-B/postes")
                        .asString(),
                as(sessionBruno).get("/api/espace-animateur/" + tokenBruno).asString(),
                given().header("X-Edition-Id", "E1").get("/api/animateurs").asString(),
                given().header("X-Edition-Id", "E1")
                        .get("/api/animateurs/CT-A/fiche")
                        .asString(),
                given().header("X-Edition-Id", "E1")
                        .get("/api/planning/export-scenario")
                        .asString(),
                given().header("X-Edition-Id", "E1")
                        .get("/api/referentiels/export?type=animateurs")
                        .asString());
        for (String body : doors) {
            assertThat(body).doesNotContain(urgence).doesNotContain(nom);
        }
    }
}
