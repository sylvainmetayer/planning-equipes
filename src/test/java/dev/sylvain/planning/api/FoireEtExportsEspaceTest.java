package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Ouverture/fermeture de la foire au planning et téléchargements de l'espace
 * (suite de l'issue #165) : la fermeture est un refus côté serveur, pas un
 * simple masquage d'interface, et l'espace reste consultable — planning,
 * PDF et ICS compris — foire fermée.
 */
@QuarkusTest
class FoireEtExportsEspaceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 12);
    private static final long CRENEAU_ID = 9301L;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

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
        persistence.persist(new PlanningFestival(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));
    }

    /** The foire state is shared, edition-wide: every test leaves it open. */
    @AfterEach
    void rouvrirLaFoire() {
        configurer(true);
    }

    @Test
    void fermerLaFoireBloqueLesSoumissionsEtAnnulationsCoteServeur() {
        String jeton = jetonDe("FOIRE-A");

        // Open by default, and a demande goes through.
        given().when().get("/api/echanges/configuration")
                .then().statusCode(200).body("foireOuverte", equalTo(true));
        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"FOIRE-S1\",\"cibleId\":\"FOIRE-B\"}]")
                .when().post("/api/espace-animateur/" + jeton + "/demandes")
                .then().statusCode(200)
                .extract().path("[0].id");

        // Closing is immediate and enforced server-side.
        configurer(false);
        given().when().get("/api/echanges/configuration")
                .then().statusCode(200).body("foireOuverte", equalTo(false));

        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"FOIRE-S1\",\"cibleId\":\"FOIRE-B\"}]")
                .when().post("/api/espace-animateur/" + jeton + "/demandes")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + jeton + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(400)
                .body("message", containsString("fermée"));

        // The espace stays consultable and says the foire is closed.
        given().when().get("/api/espace-animateur/" + jeton)
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(false))
                .body("postes.size()", equalTo(1));

        // Reopening restores the whole flow, cancellation included.
        configurer(true);
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + jeton + "/demandes/" + demandeId + "/annulation")
                .then().statusCode(204);
    }

    @Test
    void lEspaceTelechargeSonPlanningEnPdfEtIcsMemeFoireFermee() {
        String jeton = jetonDe("FOIRE-A");
        configurer(false);

        byte[] pdf = given().when().get("/api/espace-animateur/" + jeton + "/planning.pdf")
                .then()
                .statusCode(200)
                .contentType("application/pdf")
                .header("Content-Disposition", containsString("planning-Alice-Martin.pdf"))
                .extract().asByteArray();
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String ics = given().when().get("/api/espace-animateur/" + jeton + "/planning.ics")
                .then()
                .statusCode(200)
                .contentType(containsString("text/calendar"))
                .header("Content-Disposition", containsString("planning-Alice-Martin.ics"))
                .extract().asString();
        assertThat(ics).startsWith("BEGIN:VCALENDAR").contains("Stand foire un");
    }

    @Test
    void unJetonInconnuNeTelechargeRien() {
        given().when().get("/api/espace-animateur/jeton-invente/planning.pdf")
                .then().statusCode(404);
        given().when().get("/api/espace-animateur/jeton-invente/planning.ics")
                .then().statusCode(404);
    }

    private static void configurer(boolean ouverte) {
        given().contentType(ContentType.JSON)
                .body("{\"foireOuverte\":" + ouverte + "}")
                .when().put("/api/echanges/configuration")
                .then()
                .statusCode(200)
                .body("foireOuverte", equalTo(ouverte));
    }

    private String jetonDe(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getJetonAcces();
    }
}
