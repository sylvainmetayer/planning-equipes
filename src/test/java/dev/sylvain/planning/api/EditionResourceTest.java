package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code edition} scope end-to-end: creating an edition, working in it
 * through {@code X-Edition-Id}, duplicating one into another, and the guards
 * around deletion. See {@code docs/editions.md}.
 */
@QuarkusTest
class EditionResourceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String DEFAUT = "DEFAUT";

    /**
     * Every test creates its own edition(s); dropping them afterwards keeps the
     * shared dev-services database as this class found it. The default edition
     * is never touched, so an unrelated test never sees a leftover edition.
     */
    @AfterEach
    void supprimerLesEditionsCreees() {
        for (Map<String, Object> edition : listerEditions()) {
            String id = (String) edition.get("id");
            if (!DEFAUT.equals(id)) {
                given().when().delete("/api/editions/" + id);
            }
        }
    }

    private List<Map<String, Object>> listerEditions() {
        return given().when().get("/api/editions").then().statusCode(200).extract().jsonPath().getList("$");
    }

    private void creerEdition(String id, String nom) {
        given().contentType("application/json")
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\"}")
                .when().post("/api/editions")
                .then().statusCode(200);
    }

    private void creerStand(String editionId, String standId) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"" + standId + "\",\"nom\":\"" + standId + "\",\"effectifMin\":1,\"effectifMax\":2}")
                .when().post("/api/stands")
                .then().statusCode(200);
    }

    private List<String> listerStandIds(String editionId) {
        return given().header(HEADER, editionId)
                .when().get("/api/stands")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
    }

    @Test
    void uneBaseNeuveTientUneSeuleEditionParDefaut() {
        assertThat(listerEditions())
                .filteredOn(edition -> DEFAUT.equals(edition.get("id")))
                .singleElement()
                .satisfies(edition -> assertThat(edition.get("defaut")).isEqualTo(true));
    }

    @Test
    void lesDonneesDuneEditionSontInvisiblesDepuisUneAutre() {
        creerEdition("ANNEE-2026", "Année 2026");
        creerStand("ANNEE-2026", "STAND-2026");

        assertThat(listerStandIds("ANNEE-2026")).contains("STAND-2026");
        assertThat(listerStandIds(DEFAUT)).doesNotContain("STAND-2026");
    }

    @Test
    void deuxEditionsPeuventPorterLeMemeIdentifiantMetier() {
        creerEdition("ANNEE-2026", "Année 2026");
        creerStand(DEFAUT, "TIR-A-LA-CORDE");
        // Same business id in another edition: this is exactly what the
        // composite (edition_id, id) primary keys of V32 make possible.
        creerStand("ANNEE-2026", "TIR-A-LA-CORDE");

        assertThat(listerStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
        assertThat(listerStandIds("ANNEE-2026")).contains("TIR-A-LA-CORDE");

        given().header(HEADER, "ANNEE-2026").when().delete("/api/stands/TIR-A-LA-CORDE").then().statusCode(204);

        assertThat(listerStandIds("ANNEE-2026")).doesNotContain("TIR-A-LA-CORDE");
        assertThat(listerStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
    }

    @Test
    void unEnteteInconnuRetombeSurLEditionParDefautSansEchouer() {
        creerStand(DEFAUT, "STAND-REPLI");

        // A tab left open on a since-deleted edition must keep working.
        assertThat(listerStandIds("EDITION-QUI-NEXISTE-PAS")).contains("STAND-REPLI");

        given().header(HEADER, "EDITION-QUI-NEXISTE-PAS")
                .when().get("/api/editions/courant")
                .then().statusCode(200)
                .body("id", org.hamcrest.Matchers.equalTo(DEFAUT));
    }

    @Test
    void dupliquerUneEditionRecopieSonReferentielMaisPasSesAffectations() {
        creerStand(DEFAUT, "STAND-A-COPIER");

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/editions/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        assertThat(listerStandIds("COPIE-2026")).contains("STAND-A-COPIER");
        given().header(HEADER, "COPIE-2026")
                .when().get("/api/planning/persisted/count")
                .then().statusCode(200)
                .body("assignments", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void uneEditionDupliqueeEstIndependanteDeSaSource() {
        creerStand(DEFAUT, "STAND-PARTAGE");
        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/editions/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        given().header(HEADER, "COPIE-2026").when().delete("/api/stands/STAND-PARTAGE").then().statusCode(204);

        assertThat(listerStandIds("COPIE-2026")).doesNotContain("STAND-PARTAGE");
        assertThat(listerStandIds(DEFAUT)).contains("STAND-PARTAGE");
    }

    @Test
    void supprimerLEditionParDefautEstRefuse() {
        creerEdition("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when().delete("/api/editions/" + DEFAUT)
                .then().statusCode(400);
    }

    @Test
    void supprimerLEditionCouranteEstRefuse() {
        creerEdition("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when().delete("/api/editions/ANNEE-2026")
                .then().statusCode(400);
    }

    @Test
    void creerDeuxFoisLeMemeIdentifiantEstRefuse() {
        creerEdition("ANNEE-2026", "Année 2026");

        given().contentType("application/json")
                .body("{\"id\":\"ANNEE-2026\",\"nom\":\"Doublon\"}")
                .when().post("/api/editions")
                .then().statusCode(400);
    }
}
