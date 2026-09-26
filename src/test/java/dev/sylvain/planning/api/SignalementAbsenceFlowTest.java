package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * « Je ne pourrai pas être là » (issue #533) end to end: reported from the
 * espace on a day or on a seat of the published plan, told to the
 * organisation at once, read on the day's screen, withdrawn while nobody has
 * settled it, and settled either way — filed, or observed with the absence
 * marked. Reporting writes nothing to the plan: that half of the assertions is
 * about the seats staying still.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SignalementAbsenceFlowTest {

    /** Far enough ahead that « cette journée est passée » never applies, whatever day the suite runs. */
    private static final LocalDate JOUR = LocalDate.now().plusDays(40);

    private static final String EMAIL_ALICE = "sig-alice@example.org";
    private static final String ADMIN = "admin@example.org";

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

    private String token;

    private long creneauId;

    @BeforeEach
    void seed() {
        RestAssured.requestSpecification = null;
        forget();
        persistence.clearDatabase();
        Animateur alice = new Animateur("SIG-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("SIG-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("SIG-S1", "Stand signalé", Set.of(), 1, 1, false);
        Creneau matin = new Creneau(9501L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau apresMidi = new Creneau(9502L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(17, 0));
        PosteAffectation p1 = new PosteAffectation("SIG-P1", stand, matin);
        p1.setAnimateur(alice);
        PosteAffectation p2 = new PosteAffectation("SIG-P2", stand, apresMidi);
        p2.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(p1, p2)));
        creneauId = 9501L;
        Animateur fiche = fiche("SIG-A");
        fiche.setEmail(EMAIL_ALICE);
        referenceData.updateAnimateur("SIG-A", fiche);
        PlansPublies.publier(publication);
        token = fiche("SIG-A").getAccessToken();
        mailbox.clear();
        String session = EspaceSessions.open(mailbox, token, EMAIL_ALICE);
        mailbox.clear();
        RestAssured.requestSpecification =
                new RequestSpecBuilder().addCookie("planning-espace", session).build();
    }

    @AfterEach
    void cleanUp() {
        RestAssured.requestSpecification = null;
        forget();
    }

    private void forget() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM signalement_absence");
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the fixture", e);
        }
    }

    @Test
    void aReportedDayReachesTheOrganisationAndTheDayScreenWithoutTouchingThePlan() {
        JsonPath signalements = signaler(Map.of("portee", "JOUR", "date", JOUR.toString(), "motif", "TRANSPORT"))
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(signalements.getList("statut")).containsExactly("SIGNALE");
        assertThat(signalements.getString("[0].motif")).isEqualTo("TRANSPORT");
        // Told at once, best effort, with the reason worded and no free text.
        List<Mail> mails = mailbox.getMailsSentTo(ADMIN);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).contains("empêchement signalé par Alice Martin");
        assertThat(mails.get(0).getText()).contains("toute la journée").contains("transport");
        // The espace reads it back.
        given().when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200)
                .body("signalements[0].statut", equalTo("SIGNALE"));
        // Nothing moved in the plan: both seats still Alice's.
        assertThat(persistence.loadPersistedPlanning().getPostes().stream()
                        .filter(poste -> poste.getAnimateur() != null)
                        .map(poste -> poste.getAnimateur().getId()))
                .containsExactly("SIG-A", "SIG-A");
        // The day's screen lists it, named.
        RestAssured.requestSpecification = null;
        JsonPath jour = given().when()
                .get("/api/jour-j?date=" + JOUR)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(jour.getList("signalements.nomAffiche")).containsExactly("Alice Martin");
        assertThat(jour.getString("signalements[0].portee")).isEqualTo("JOUR");
    }

    @Test
    void theSameReportTwiceIsOneReportAndAForeignSeatIsRefused() {
        signaler(Map.of("portee", "POSTE", "date", JOUR.toString(), "creneauId", creneauId, "standId", "SIG-S1"))
                .statusCode(200);
        signaler(Map.of("portee", "POSTE", "date", JOUR.toString(), "creneauId", creneauId, "standId", "SIG-S1"))
                .statusCode(409);
        // A seat that is not hers in the published plan.
        signaler(Map.of("portee", "POSTE", "date", JOUR.toString(), "creneauId", 424242, "standId", "SIG-S1"))
                .statusCode(400);
        // A day she holds no seat on.
        signaler(Map.of("portee", "JOUR", "date", JOUR.plusDays(1).toString())).statusCode(400);
        // A day already over.
        signaler(Map.of("portee", "JOUR", "date", LocalDate.now().minusDays(1).toString()))
                .statusCode(400);
    }

    @Test
    void aReportIsWithdrawnWhileOpenAndNotOnceSettled() {
        long id = signaler(Map.of("portee", "JOUR", "date", JOUR.toString()))
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");

        given().when()
                .delete("/api/espace-animateur/" + token + "/signalements/" + id)
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("ANNULE"));

        long second = signaler(Map.of("portee", "JOUR", "date", JOUR.toString()))
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("find { it.statut == 'SIGNALE' }.id");
        RestAssured.requestSpecification = null;
        given().when()
                .post("/api/jour-j/signalements/" + second + "/classement")
                .then()
                .statusCode(204);
        given().when()
                .post("/api/jour-j/signalements/" + second + "/classement")
                .then()
                .statusCode(409);
    }

    /**
     * « Marquer absent et remplacer » on a seat: the absence is marked on that
     * timeslot only, the seat is freed, and the report settles.
     */
    @Test
    void observingASeatReportMarksTheAbsenceOnThatTimeslotAndFreesTheSeat() {
        long id = signaler(
                        Map.of("portee", "POSTE", "date", JOUR.toString(), "creneauId", creneauId, "standId", "SIG-S1"))
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");
        RestAssured.requestSpecification = null;

        JsonPath marquee = given().when()
                .post("/api/jour-j/signalements/" + id + "/traitement")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(marquee.getList("postesLiberes.creneauId", Long.class)).containsExactly(creneauId);
        assertThat(referenceData.listContraintesAdHoc().stream()
                        .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                        .filter(contrainte -> contrainte.getCreneau().getId() == creneauId))
                .hasSize(1);
        assertThat(persistence.loadPersistedPlanning().getPostes().stream()
                        .filter(poste -> poste.getAnimateur() != null)
                        .map(poste -> poste.getCreneau().getId()))
                .containsExactly(9502L);
        given().when().get("/api/jour-j?date=" + JOUR).then().statusCode(200).body("signalements.size()", equalTo(0));
        referenceData.listContraintesAdHoc().stream()
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                .forEach(contrainte -> referenceData.deleteContrainteAdHoc(contrainte.getId()));
    }

    /**
     * The ceiling of the declaration, on its own counter: past it, {@code 429}
     * with a {@code Retry-After}. Counted before any validation, so a loop of
     * refused reports meets it too. Last of the class: the counter is the
     * JVM's, and it would refuse the other tests' reports for ten minutes.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void aLoopOfReportsMeetsTheCeiling() {
        int statut = 0;
        for (int envoi = 0; envoi < 40 && statut != 429; envoi++) {
            statut = signaler(Map.of("portee", "JOUR", "date", JOUR.toString()))
                    .extract()
                    .statusCode();
        }

        assertThat(statut).isEqualTo(429);
        signaler(Map.of("portee", "JOUR", "date", JOUR.toString()))
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue());
    }

    private io.restassured.response.ValidatableResponse signaler(Map<String, Object> corps) {
        return given().contentType(ContentType.JSON)
                .body(corps)
                .when()
                .post("/api/espace-animateur/" + token + "/signalements")
                .then();
    }

    private Animateur fiche(String id) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
