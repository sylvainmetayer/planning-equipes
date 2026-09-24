package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.OidcJetons;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The espace animateur behind Keycloak (ADR 0049): the link alone is not
 * enough, a Keycloak session carrying the {@code animateur} role and the
 * <b>verified</b> address of the fiche the link designates opens it. The
 * tokens are signed by the in-memory OIDC server, so the guard under test is
 * the production one.
 */
@QuarkusTest
class EspaceAccesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 14);
    private static final long CRENEAU_ID = 9501L;
    private static final String EMAIL_ALICE = "acces-alice@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    CompteService comptes;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        Animateur alice = new Animateur("ACCES-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ACCES-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("ACCES-S1", "Stand accès", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation poste = new PosteAffectation("ACCES-P1", stand, creneau);
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(poste)));
        donnerEmail("ACCES-A", EMAIL_ALICE);
        donnerEmail("ACCES-B", null);
    }

    /** The whole espace — downloads included — answers 401 without a session. */
    @Test
    void sansSessionToutLEspaceRepond401() {
        String token = tokenOf("ACCES-A");
        given().when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(401)
                .body("message", containsString("connectez-vous"));
        given().when()
                .get("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(401);
        given().when()
                .get("/api/espace-animateur/" + token + "/planning.pdf")
                .then()
                .statusCode(401);
        given().when()
                .get("/api/espace-animateur/" + token + "/planning.ics")
                .then()
                .statusCode(401);
        // An unknown token stays a plain 404: nothing must help guessing one.
        given().when().get("/api/espace-animateur/jeton-invente").then().statusCode(404);
    }

    @Test
    void laSessionKeycloakOuvreLEspaceDeSaPropreFiche() {
        String token = tokenOf("ACCES-A");
        String session = EspaceSessions.open(EMAIL_ALICE);

        given().header(EspaceSessions.EN_TETE, session)
                .when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200)
                .body("animateurId", equalTo("ACCES-A"));
        given().header(EspaceSessions.EN_TETE, session)
                .when()
                .get("/api/espace-animateur/" + token + "/planning.pdf")
                .then()
                .statusCode(200);
    }

    /** The realm may spell the address differently: the comparison ignores case and blanks. */
    @Test
    void laCasseDeLAdresseNeComptePas() {
        given().header(EspaceSessions.EN_TETE, EspaceSessions.open("Acces-Alice@Example.org"))
                .when()
                .get("/api/espace-animateur/" + tokenOf("ACCES-A"))
                .then()
                .statusCode(200);
    }

    /**
     * The case the address match exists for: a colleague's link, picked up
     * from a printed planning, opened by someone who <em>is</em> correctly
     * signed in. Authentication is not authorisation.
     */
    @Test
    void laSessionDUnAnimateurNOuvrePasLEspaceDUnAutre() {
        given().header(EspaceSessions.EN_TETE, EspaceSessions.open(EMAIL_ALICE))
                .when()
                .get("/api/espace-animateur/" + tokenOf("ACCES-B"))
                .then()
                .statusCode(401);
    }

    /**
     * An address the identity provider never confirmed is an address anyone
     * able to create an account could have typed. The role IS carried and the
     * address IS the fiche's: only {@code email_verified: false} is wrong.
     */
    @Test
    void uneAdresseNonVerifieeNOuvreRien() {
        String jeton = OidcJetons.jeton("imposteur", List.of("user", "animateur"), "planning-app", EMAIL_ALICE, false);
        given().header(EspaceSessions.EN_TETE, "Bearer " + jeton)
                .when()
                .get("/api/espace-animateur/" + tokenOf("ACCES-A"))
                .then()
                .statusCode(401);
    }

    /**
     * {@code user} is the role Keycloak hands every account: being signed in is
     * not being an animateur, so the next role added to the realm arrives with
     * no access rather than with this one.
     */
    @Test
    void leRoleOrdinaireUserNOuvrePasLEspace() {
        String jeton = OidcJetons.jeton("alice", List.of("user"), "planning-app", EMAIL_ALICE, true);
        given().header(EspaceSessions.EN_TETE, "Bearer " + jeton)
                .when()
                .get("/api/espace-animateur/" + tokenOf("ACCES-A"))
                .then()
                .statusCode(401);
    }

    /** Deactivating the account in the application closes the espace, whatever the realm says. */
    @Test
    void unCompteDesactiveNOuvrePlusLEspace() {
        String token = tokenOf("ACCES-A");
        String session = EspaceSessions.open(EMAIL_ALICE);
        given().header(EspaceSessions.EN_TETE, session)
                .when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200);
        String compteId = comptes.list().stream()
                .filter(compte -> compte.email().equals(EMAIL_ALICE))
                .findFirst()
                .orElseThrow()
                .id();
        comptes.deactivate(compteId);
        try {
            given().header(EspaceSessions.EN_TETE, session)
                    .when()
                    .get("/api/espace-animateur/" + token)
                    .then()
                    .statusCode(401);
        } finally {
            comptes.reactivate(compteId);
        }
    }

    /** The code routes are gone with the code: nothing is left to ask for one. */
    @Test
    void lesRoutesDuCodeEMailNExistentPlus() {
        String token = tokenOf("ACCES-A");
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/code")
                .then()
                .statusCode(404);
        given().contentType(ContentType.JSON)
                .body("{\"code\":\"123456\"}")
                .when()
                .post("/api/espace-animateur/" + token + "/session")
                .then()
                .statusCode(404);
    }

    /**
     * A download refused for want of a session writes nothing: it is a free
     * read anyone holding — or guessing at — a link can repeat, and a line per
     * attempt would hand the table to them.
     */
    @Test
    void aDownloadRefusedWithoutSessionWritesNothing() throws Exception {
        String token = tokenOf("ACCES-A");
        clearJournal("TELECHARGEMENT_ESPACE_PDF");

        given().when()
                .get("/api/espace-animateur/" + token + "/planning.pdf")
                .then()
                .statusCode(401);

        assertThat(journalLines("TELECHARGEMENT_ESPACE_PDF")).isEmpty();
    }

    /** Behind the Keycloak session the download is the animateur's own, and says so. */
    @Test
    void aDownloadWithAKeycloakSessionIsRecordedUnderTheAnimateur() throws Exception {
        String token = tokenOf("ACCES-A");
        String session = EspaceSessions.open(EMAIL_ALICE);
        clearJournal("TELECHARGEMENT_ESPACE_PDF");

        given().header(EspaceSessions.EN_TETE, session)
                .when()
                .get("/api/espace-animateur/" + token + "/planning.pdf")
                .then()
                .statusCode(200);

        assertThat(journalLines("TELECHARGEMENT_ESPACE_PDF"))
                .singleElement()
                .isEqualTo(new JournalLine("ANIMATEUR", "ACCES-A", "ACCES-A", 200));
    }

    /** Holding the link proves nothing: a write refused without a session is ANONYME. */
    @Test
    void withoutASessionNobodyIsNamed() throws Exception {
        String token = tokenOf("ACCES-A");
        clearJournal("PLANNING_CONFIRME");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/confirmation")
                .then()
                .statusCode(401);

        assertThat(journalLines("PLANNING_CONFIRME"))
                .singleElement()
                .isEqualTo(new JournalLine("ANONYME", null, "ACCES-A", 401));
    }

    /** One line of the history, as far as these tests read it. */
    private record JournalLine(String acteur, String acteurId, String entiteId, int statut) {}

    private void clearJournal(String action) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM journal_action WHERE action = ?")) {
            ps.setString(1, action);
            ps.executeUpdate();
        }
    }

    private List<JournalLine> journalLines(String action) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT acteur, acteur_id, entite_id, statut FROM journal_action WHERE action = ?")) {
            ps.setString(1, action);
            try (ResultSet rows = ps.executeQuery()) {
                List<JournalLine> lignes = new java.util.ArrayList<>();
                while (rows.next()) {
                    lignes.add(new JournalLine(
                            rows.getString("acteur"),
                            rows.getString("acteur_id"),
                            rows.getString("entite_id"),
                            rows.getInt("statut")));
                }
                return lignes;
            }
        }
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }
}
