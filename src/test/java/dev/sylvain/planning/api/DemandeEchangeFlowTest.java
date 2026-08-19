package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * End-to-end foire au planning (issue #165) against the real PostgreSQL
 * container: a persisted two-seat planning, the espace animateur reached by
 * the database-generated token, a submitted demande with its prevalidation,
 * the fresh admin impact, and an acceptation that applies the swap and pins
 * both animateurs on the créneau.
 */
@QuarkusTest
class DemandeEchangeFlowTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final long CRENEAU_ID = 9101L;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Test
    void unEchangeCroiseEstSoumisPuisAccepteEtApplique() {
        persisterPlanningDeuxSieges();
        String jeton = jetonDe("ECH-A");

        given().when().get("/api/espace-animateur/" + jeton)
                .then()
                .statusCode(200)
                .body("animateurId", equalTo("ECH-A"))
                .body("postes.size()", greaterThanOrEqualTo(1))
                .body("collegues.find { it.id == 'ECH-B' }.nomComplet", equalTo("Bruno Petit"));

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\","
                        + "\"cibleId\":\"ECH-B\",\"motif\":\"rendez-vous médical\"}]")
                .when().post("/api/espace-animateur/" + jeton + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("PROPOSEE"))
                .body("[0].prevalidationOk", notNullValue())
                .body("[0].cibleNom", equalTo("Bruno Petit"))
                .extract().path("[0].id");

        given().when().get("/api/echanges/" + demandeId + "/impact")
                .then()
                .statusCode(200)
                .body("echangeCroise", equalTo(true))
                .body("standCibleId", equalTo("ECH-S2"));

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(200)
                .body("statut", equalTo("ACCEPTEE"));

        // The swap was applied exactly as simulated: seats traded, nothing else.
        PlanningFestival apres = persistence.loadPersistedPlanning();
        assertThat(occupant(apres, "ECH-S1")).isEqualTo("ECH-B");
        assertThat(occupant(apres, "ECH-S2")).isEqualTo("ECH-A");

        // Both animateurs are pinned on the créneau by ANIMATEUR_CRENEAU locks.
        assertThat(referenceData.listVerrouillages())
                .filteredOn(v -> v.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU
                        && v.getCreneauId() != null && v.getCreneauId() == CRENEAU_ID)
                .extracting(v -> v.getAnimateurId())
                .contains("ECH-A", "ECH-B");

        // A decided demande can no longer be refused (or re-accepted).
        given().contentType(ContentType.JSON).body("{\"commentaire\":\"non\"}")
                .when().post("/api/echanges/" + demandeId + "/refus")
                .then()
                .statusCode(400);
    }

    @Test
    void unJetonInconnuRepondIntrouvable() {
        given().when().get("/api/espace-animateur/jeton-invente")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    void leDemandeurPeutAnnulerUneDemandeEnAttente() {
        persisterPlanningDeuxSieges();
        String jeton = jetonDe("ECH-A");

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + jeton + "/demandes")
                .then()
                .statusCode(200)
                .extract().path("[0].id");

        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + jeton + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(204);

        given().when().get("/api/espace-animateur/" + jeton + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("ANNULEE"));

        // An already-cancelled demande cannot be decided.
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);
    }

    /** Two singleton stands on the same créneau: Alice on S1, Bruno on S2. */
    private void persisterPlanningDeuxSieges() {
        Animateur alice = new Animateur("ECH-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ECH-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand standUn = new Stand("ECH-S1", "Stand un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("ECH-S2", "Stand deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteUn = new PosteAffectation("ECH-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("ECH-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningFestival(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));
    }

    /** Occupant of the single seat of {@code standId} on the test créneau. */
    private static String occupant(PlanningFestival planning, String standId) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == CRENEAU_ID)
                .findFirst()
                .map(poste -> poste.getAnimateur() == null ? null : poste.getAnimateur().getId())
                .orElse(null);
    }

    /** The database-generated espace token of one seeded animateur. */
    private String jetonDe(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getJetonAcces();
    }
}
