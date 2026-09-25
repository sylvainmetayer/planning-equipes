package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/animateurs/{id}/fiche} assembles what the specialised screens
 * say about one person: these tests hold the fiche to the very figures the
 * Équité and Fragilité screens show for that person, on the same plan.
 */
@QuarkusTest
class AnimateurProfileResourceTest {

    /** Saturday 11 July 2026. */
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);

    @Inject
    PlanningPersistenceService persistence;

    @BeforeEach
    void seed() {
        persistence.clearDatabase();
    }

    @Test
    void anUnknownIdIsA404() {
        given().when().get("/api/animateurs/FI-INCONNU/fiche").then().statusCode(404);
    }

    @Test
    void withoutAPersistedPlanTheDependentSectionsAreEmptyAndSaySo() {
        Animateur alice = new Animateur("FI-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        persistence.persist(new PlanningEvenement(SAMEDI, List.of(alice), List.of()));

        given().when()
                .get("/api/animateurs/FI-A/fiche")
                .then()
                .statusCode(200)
                .body("animateur.id", equalTo("FI-A"))
                .body("planCalcule", equalTo(false))
                .body("equite.lignes", hasSize(0))
                .body("fragilite", nullValue())
                .body("affectations", hasSize(0));
    }

    @Test
    void theEquityLineAndTheFragileSeatsAreTheOnesTheSpecialisedScreensShow() {
        // A minor turning 18 during the event, to read both regimes too.
        Animateur alice = new Animateur("FI-A", "Alice", "Martin", LocalDate.of(2008, 7, 12), false);
        Animateur bruno = new Animateur("FI-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur libre = new Animateur("FI-C", "Chloé", "Sans", LocalDate.of(1994, 3, 3), false);
        Stand stand = new Stand("FI-S1", "Stand de la fiche", Set.of(), 2, 2, false);
        Creneau samedi = new Creneau(9701L, 1, SAMEDI, LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau dimanche = new Creneau(9702L, 2, SAMEDI.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));
        PosteAffectation p1 = new PosteAffectation("FI-P1", stand, samedi);
        p1.setAnimateur(alice);
        PosteAffectation p2 = new PosteAffectation("FI-P2", stand, samedi);
        p2.setAnimateur(bruno);
        PosteAffectation p3 = new PosteAffectation("FI-P3", stand, dimanche);
        p3.setAnimateur(alice);
        PosteAffectation p4 = new PosteAffectation("FI-P4", stand, dimanche);
        persistence.persist(new PlanningEvenement(SAMEDI, List.of(alice, bruno, libre), List.of(p1, p2, p3, p4)));

        JsonPath fiche = given().when()
                .get("/api/animateurs/FI-A/fiche")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        JsonPath equite = given().when()
                .get("/api/planning/equite")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        JsonPath fragilite = given().when()
                .get("/api/fragilite")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(fiche.getBoolean("planCalcule")).isTrue();
        assertThat(fiche.getList("equite.lignes")).hasSize(1);
        Map<String, Object> ligneFiche = fiche.getMap("equite.lignes[0]");
        Map<String, Object> ligneEcran = equite.getMap("lignes.find { it.animateurId == 'FI-A' }");
        assertThat(ligneFiche).isEqualTo(ligneEcran);
        assertThat(fiche.getMap("equite.syntheses")).isEqualTo(equite.getMap("syntheses"));

        Map<String, Object> fragiliteFiche = fiche.getMap("fragilite");
        Map<String, Object> fragiliteEcran = fragilite.getMap("animateurs.find { it.animateurId == 'FI-A' }");
        assertThat(fragiliteFiche).isNotNull().isEqualTo(fragiliteEcran);
        assertThat(fiche.getList("fragilite.postes")).isNotEmpty();

        assertThat(fiche.getList("affectations.posteId")).containsExactly("FI-P1", "FI-P3");
        assertThat(fiche.getString("affectations[0].standNom")).isEqualTo("Stand de la fiche");
        assertThat(fiche.getString("regimeDebut.regime")).isEqualTo("MINEUR");
        assertThat(fiche.getString("regimeFin.regime")).isEqualTo("MAJEUR");
    }

    @Test
    void anAnimateurWithoutASeatHasNoEquityLineButTheSyntheses() {
        Animateur alice = new Animateur("FI-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur libre = new Animateur("FI-C", "Chloé", "Sans", LocalDate.of(1994, 3, 3), false);
        Stand stand = new Stand("FI-S1", "Stand de la fiche", Set.of(), 1, 1, false);
        Creneau samedi = new Creneau(9703L, 1, SAMEDI, LocalTime.of(14, 0), LocalTime.of(18, 0));
        PosteAffectation p1 = new PosteAffectation("FI-P1", stand, samedi);
        p1.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(SAMEDI, List.of(alice, libre), List.of(p1)));

        given().when()
                .get("/api/animateurs/FI-C/fiche")
                .then()
                .statusCode(200)
                .body("planCalcule", equalTo(true))
                .body("equite.lignes", hasSize(0))
                .body("equite.syntheses.heuresTotal.mediane", equalTo(4.0f))
                .body("fragilite", nullValue())
                .body("affectations", hasSize(0))
                .body("regimeDebut.regime", equalTo("MAJEUR"));
    }
}
