package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/planning/equite} reads the persisted plan under the
 * organiser's current legal parameters — a read-out, so none of these tests
 * ever solves.
 */
@QuarkusTest
class EquiteResourceTest {

    /** Saturday 11 July 2026. */
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);

    @Inject
    PlanningPersistenceService persistence;

    @BeforeEach
    void seed() {
        persistence.clearDatabase();
        declarerHeureDebutSoiree("20:00:00");
    }

    @Test
    void withoutAPersistedPlanTheTableIsEmptyAndSaysSoWithoutAnError() {
        given().when()
                .get("/api/planning/equite")
                .then()
                .statusCode(200)
                .body("lignes.size()", equalTo(0))
                .body("semaines.size()", equalTo(0))
                .body("syntheses.size()", equalTo(0))
                .body("heureDebutSoiree", equalTo("20:00:00"))
                .body("colonnesSolveur.contrainte", hasItem("equilibrerCharge"));
    }

    @Test
    void theTableHasOneLinePerAssignedAnimateurAndFollowsTheEveningHourDeclaredToday() {
        Animateur alice = new Animateur("EQ-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("EQ-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur libre = new Animateur("EQ-C", "Chloé", "Sans", LocalDate.of(1994, 3, 3), false);
        Stand stand = new Stand("EQ-S1", "Stand de l'équité", Set.of(), 2, 2, false);
        Creneau soir = new Creneau(9601L, 1, SAMEDI, LocalTime.of(17, 0), LocalTime.of(23, 0));
        PosteAffectation posteAlice = new PosteAffectation("EQ-P1", stand, soir);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("EQ-P2", stand, soir);
        posteBruno.setAnimateur(bruno);
        persistence.persist(
                new PlanningEvenement(SAMEDI, List.of(alice, bruno, libre), List.of(posteAlice, posteBruno)));

        given().when()
                .get("/api/planning/equite")
                .then()
                .statusCode(200)
                .body("lignes", hasSize(2))
                .body("lignes[0].animateurId", equalTo("EQ-A"))
                .body("lignes[0].nom", equalTo("Alice Martin"))
                .body("lignes[0].heuresTotal", equalTo(6.0f))
                .body("lignes[0].heuresSoiree", equalTo(3.0f))
                .body("lignes[0].heuresWeekEnd", equalTo(6.0f))
                .body("lignes[0].postes", equalTo(1))
                .body("semaines[0]", equalTo("2026-W28"))
                .body("syntheses.heuresTotal.mediane", equalTo(6.0f))
                .body("syntheses.heuresTotal.ecartType", equalTo(0.0f));

        // The evening moved: the same plan, read under today's declaration.
        declarerHeureDebutSoiree("22:00:00");
        given().when()
                .get("/api/planning/equite")
                .then()
                .statusCode(200)
                .body("heureDebutSoiree", equalTo("22:00:00"))
                .body("lignes[0].heuresSoiree", equalTo(1.0f));
    }

    @Test
    void theCsvExportCarriesTheByteOrderMarkAndOneLinePerAnimateur() {
        Animateur alice = new Animateur("EQ-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Stand stand = new Stand("EQ-S1", "Stand de l'équité", Set.of(), 1, 1, false);
        Creneau matin = new Creneau(9602L, 1, SAMEDI, LocalTime.of(9, 0), LocalTime.of(12, 0));
        PosteAffectation poste = new PosteAffectation("EQ-P1", stand, matin);
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(SAMEDI, List.of(alice), List.of(poste)));

        byte[] corps = given().when()
                .get("/api/planning/equite/export")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("text/csv"))
                .header("Content-Disposition", equalTo("attachment; filename=\"equite-planning.csv\""))
                .extract()
                .asByteArray();

        assertThat(corps).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(corps, java.nio.charset.StandardCharsets.UTF_8).substring(1);
        assertThat(csv).startsWith("animateur;heuresTotal;2026-W28;");
        assertThat(csv).contains("\nAlice Martin;3,00;3,00;0,00;3,00;0,00;1;");
    }

    private static void declarerHeureDebutSoiree(String heure) {
        String courant = given().when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        String modifie =
                courant.replaceAll("\"heureDebutSoiree\":\"[0-9:]+\"", "\"heureDebutSoiree\":\"" + heure + "\"");
        given().contentType(ContentType.JSON)
                .body(modifie)
                .when()
                .put("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("heureDebutSoiree", equalTo(heure));
    }
}
