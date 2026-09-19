package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

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
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;

/**
 * Opening and closing the foire au planning, and the espace downloads
 * (follow-up to issue #165): closing is a server-side refusal, not merely a
 * hidden button, and the espace stays readable — planning, PDF and ICS
 * included — while the foire is closed.
 */
@QuarkusTest
class FoireAndEspaceExportsTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 12);
    private static final long CRENEAU_ID = 9301L;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        Animateur alice = new Animateur("FOIRE-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("FOIRE-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand standUn = new Stand("FOIRE-S1", "Stand foire un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("FOIRE-S2", "Stand foire deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteUn = new PosteAffectation("FOIRE-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("FOIRE-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));

        // Alice's espace session (e-mail code flow) rides on every request.
        donnerEmail("FOIRE-A", "foire-alice@example.org");
        // The espace shows the published plan: without a publication it is empty.
        PlansPublies.publier(publication);
        mailbox.clear();
        RestAssured.requestSpecification = null;
        String session = EspaceSessions.open(mailbox, tokenOf("FOIRE-A"), "foire-alice@example.org");
        RestAssured.requestSpecification =
                new RequestSpecBuilder().addCookie("planning-espace", session).build();
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    /** The foire state is shared, edition-wide: every test leaves it open. */
    @AfterEach
    void rouvrirLaFoire() {
        configure(true);
        RestAssured.requestSpecification = null;
    }

    @Test
    void fermerLaFoireBloqueLesSoumissionsEtAnnulationsCoteServeur() {
        String token = tokenOf("FOIRE-A");

        // Open by default, and a demande goes through.
        given().when().get("/api/echanges/configuration").then().statusCode(200).body("foireOuverte", equalTo(true));
        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"FOIRE-S1\",\"cibleId\":\"FOIRE-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");

        // Closing is immediate and enforced server-side.
        configure(false);
        given().when().get("/api/echanges/configuration").then().statusCode(200).body("foireOuverte", equalTo(false));

        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"FOIRE-S1\",\"cibleId\":\"FOIRE-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));

        // The picker follows the same rule as the writes it serves: it exists
        // only to build a demande, and the interface hides it once the foire is
        // closed. Left open, it would keep serving every colleague's schedule
        // the rest of the year for no functional reason.
        given().when()
                .get("/api/espace-animateur/" + token + "/collegues/FOIRE-B/postes")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));

        // Same for « qui peut me remplacer ? » — searching partners for an
        // échange nobody may propose any more would only mislead.
        given().when()
                .get("/api/espace-animateur/" + token + "/suggestions-echange" + "?creneauId=" + CRENEAU_ID
                        + "&standId=FOIRE-S1")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));

        // The espace stays consultable and says the foire is closed.
        given().when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(false))
                .body("postes.size()", equalTo(1));

        // Reopening restores the whole flow, cancellation and picker included.
        configure(true);
        given().when()
                .get("/api/espace-animateur/" + token + "/collegues/FOIRE-B/postes")
                .then()
                .statusCode(200);
        given().when()
                .get("/api/espace-animateur/" + token + "/suggestions-echange" + "?creneauId=" + CRENEAU_ID
                        + "&standId=FOIRE-S1")
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(204);
    }

    @Test
    void lEspaceTelechargeSonPlanningEnPdfEtIcsMemeFoireFermee() {
        String token = tokenOf("FOIRE-A");
        configure(false);

        byte[] pdf = given().when()
                .get("/api/espace-animateur/" + token + "/planning.pdf")
                .then()
                .statusCode(200)
                .contentType("application/pdf")
                .header("Content-Disposition", containsString("planning-Alice-Martin.pdf"))
                .extract()
                .asByteArray();
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String ics = given().when()
                .get("/api/espace-animateur/" + token + "/planning.ics")
                .then()
                .statusCode(200)
                .contentType(containsString("text/calendar"))
                .header("Content-Disposition", containsString("planning-Alice-Martin.ics"))
                .extract()
                .asString();
        assertThat(ics).startsWith("BEGIN:VCALENDAR").contains("Stand foire un");
    }

    /**
     * The same planning under its two layouts: the booklet by default, the
     * folded sheet when asked for, and the booklet again on a format nobody
     * knows — a typo on a download deserves the usual document, not a 400.
     */
    @Test
    void lEspaceChoisitEntreLeLivretEtLaFeuilleRectoVerso() {
        String token = tokenOf("FOIRE-A");
        configure(false);

        byte[] livret = telecharge(token, "");
        byte[] feuille = telecharge(token, "?format=feuille");
        byte[] inconnu = telecharge(token, "?format=papyrus");

        assertThat(pages(feuille)).isEqualTo(2);
        assertThat(pages(livret)).isEqualTo(pages(inconnu));
        assertThat(pages(livret)).isNotEqualTo(pages(feuille));
    }

    private static byte[] telecharge(String token, String requete) {
        return given().when()
                .get("/api/espace-animateur/" + token + "/planning.pdf" + requete)
                .then()
                .statusCode(200)
                .contentType("application/pdf")
                .extract()
                .asByteArray();
    }

    private static int pages(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            try {
                return reader.getNumberOfPages();
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new AssertionError("le PDF servi est illisible", e);
        }
    }

    @Test
    void unJetonInconnuNeTelechargeRien() {
        given().when()
                .get("/api/espace-animateur/jeton-invente/planning.pdf")
                .then()
                .statusCode(404);
        given().when()
                .get("/api/espace-animateur/jeton-invente/planning.ics")
                .then()
                .statusCode(404);
    }

    private static void configure(boolean ouverte) {
        given().contentType(ContentType.JSON)
                .body("{\"foireOuverte\":" + ouverte + "}")
                .when()
                .put("/api/echanges/configuration")
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(ouverte));
    }

    /** Opens the foire, bounded by the given dates — {@code null} leaves a side unbounded. */
    private static void configureFenetre(LocalDate debut, LocalDate fin) {
        given().contentType(ContentType.JSON)
                .body("{\"foireOuverte\":true,\"debut\":" + json(debut) + ",\"fin\":" + json(fin) + "}")
                .when()
                .put("/api/echanges/configuration")
                .then()
                .statusCode(200);
    }

    private static String json(LocalDate date) {
        return date == null ? "null" : "\"" + date + "\"";
    }

    /**
     * A dated bound must be a bound, not a label: the espace hides the form
     * outside the window, and the server refuses the write regardless.
     */
    @Test
    void aSubmissionOutsideTheDatedWindowIsRefusedServerSide() {
        String token = tokenOf("FOIRE-A");
        LocalDate demain = LocalDate.now().plusDays(1);

        // The switch says open, the dates say « pas encore ».
        configureFenetre(demain, demain.plusDays(7));

        given().when()
                .get("/api/echanges/configuration")
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(true))
                .body("ouverteAujourdhui", equalTo(false));

        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"FOIRE-S1\",\"cibleId\":\"FOIRE-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));
    }

    /**
     * « Pas encore ouverte » and « fermée » are the same boolean and say the
     * opposite to the person reading, so the espace is told which one it is.
     */
    @Test
    void theEspaceIsToldWhenTheFoireHasNotStartedYet() {
        LocalDate debut = LocalDate.now().plusDays(3);
        configureFenetre(debut, null);

        given().when()
                .get("/api/espace-animateur/" + tokenOf("FOIRE-A"))
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(false))
                .body("foireOuvreLe", equalTo(debut.toString()));

        // Once the window is over there is nothing to come back for: the espace
        // must NOT be given a date, or it would announce a reopening.
        configureFenetre(LocalDate.now().minusDays(10), LocalDate.now().minusDays(3));
        given().when()
                .get("/api/espace-animateur/" + tokenOf("FOIRE-A"))
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(false))
                .body("foireOuvreLe", nullValue());
    }

    /** Refused before anything is written: the previous window stands. */
    @Test
    void anEndBeforeTheStartIsRefused() {
        given().contentType(ContentType.JSON)
                .body("{\"foireOuverte\":true,\"debut\":\"2026-07-20\",\"fin\":\"2026-07-10\"}")
                .when()
                .put("/api/echanges/configuration")
                .then()
                .statusCode(400)
                .body("message", containsString("précède"));

        given().when()
                .get("/api/echanges/configuration")
                .then()
                .statusCode(200)
                .body("debut", nullValue())
                .body("fin", nullValue());
    }

    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }
}
