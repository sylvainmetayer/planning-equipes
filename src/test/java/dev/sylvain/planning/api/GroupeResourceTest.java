package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code groupe} scope end-to-end: creating an edition, working in it
 * through {@code X-Groupe-Id}, duplicating one into another, and the guards
 * around deletion. See {@code docs/groupes.md}.
 */
@QuarkusTest
class GroupeResourceTest {

    private static final String HEADER = "X-Groupe-Id";
    private static final String DEFAUT = "DEFAUT";

    /**
     * Every test creates its own group(s); dropping them afterwards keeps the
     * shared dev-services database as this class found it. The default group is
     * never touched, so an unrelated test never sees a leftover edition.
     */
    @AfterEach
    void supprimerLesGroupesCrees() {
        for (Map<String, Object> groupe : listerGroupes()) {
            String id = (String) groupe.get("id");
            if (!DEFAUT.equals(id)) {
                given().when().delete("/api/groupes/" + id);
            }
        }
    }

    private List<Map<String, Object>> listerGroupes() {
        return given().when().get("/api/groupes").then().statusCode(200).extract().jsonPath().getList("$");
    }

    private void creerGroupe(String id, String nom) {
        given().contentType("application/json")
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\"}")
                .when().post("/api/groupes")
                .then().statusCode(200);
    }

    private void creerStand(String groupeId, String standId) {
        given().header(HEADER, groupeId)
                .contentType("application/json")
                .body("{\"id\":\"" + standId + "\",\"nom\":\"" + standId + "\",\"effectifMin\":1,\"effectifMax\":2}")
                .when().post("/api/stands")
                .then().statusCode(200);
    }

    private List<String> listerStandIds(String groupeId) {
        return given().header(HEADER, groupeId)
                .when().get("/api/stands")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
    }

    @Test
    void uneBaseNeuveTientUnSeulGroupeParDefaut() {
        assertThat(listerGroupes())
                .filteredOn(groupe -> DEFAUT.equals(groupe.get("id")))
                .singleElement()
                .satisfies(groupe -> assertThat(groupe.get("defaut")).isEqualTo(true));
    }

    @Test
    void lesDonneesDunGroupeSontInvisiblesDepuisUnAutre() {
        creerGroupe("ANNEE-2026", "Année 2026");
        creerStand("ANNEE-2026", "STAND-2026");

        assertThat(listerStandIds("ANNEE-2026")).contains("STAND-2026");
        assertThat(listerStandIds(DEFAUT)).doesNotContain("STAND-2026");
    }

    @Test
    void deuxGroupesPeuventPorterLeMemeIdentifiantMetier() {
        creerGroupe("ANNEE-2026", "Année 2026");
        creerStand(DEFAUT, "TIR-A-LA-CORDE");
        // Same business id in another edition: this is exactly what the
        // composite (groupe_id, id) primary keys of V32 make possible.
        creerStand("ANNEE-2026", "TIR-A-LA-CORDE");

        assertThat(listerStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
        assertThat(listerStandIds("ANNEE-2026")).contains("TIR-A-LA-CORDE");

        given().header(HEADER, "ANNEE-2026").when().delete("/api/stands/TIR-A-LA-CORDE").then().statusCode(204);

        assertThat(listerStandIds("ANNEE-2026")).doesNotContain("TIR-A-LA-CORDE");
        assertThat(listerStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
    }

    @Test
    void unEnteteInconnuRetombeSurLeGroupeParDefautSansEchouer() {
        creerStand(DEFAUT, "STAND-REPLI");

        // A tab left open on a since-deleted group must keep working.
        assertThat(listerStandIds("GROUPE-QUI-NEXISTE-PAS")).contains("STAND-REPLI");

        given().header(HEADER, "GROUPE-QUI-NEXISTE-PAS")
                .when().get("/api/groupes/courant")
                .then().statusCode(200)
                .body("id", org.hamcrest.Matchers.equalTo(DEFAUT));
    }

    @Test
    void dupliquerUnGroupeRecopieSonReferentielMaisPasSesAffectations() {
        creerStand(DEFAUT, "STAND-A-COPIER");

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/groupes/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        assertThat(listerStandIds("COPIE-2026")).contains("STAND-A-COPIER");
        given().header(HEADER, "COPIE-2026")
                .when().get("/api/planning/persisted/count")
                .then().statusCode(200)
                .body("assignments", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void unGroupeDupliqueEstIndependantDeSaSource() {
        creerStand(DEFAUT, "STAND-PARTAGE");
        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/groupes/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        given().header(HEADER, "COPIE-2026").when().delete("/api/stands/STAND-PARTAGE").then().statusCode(204);

        assertThat(listerStandIds("COPIE-2026")).doesNotContain("STAND-PARTAGE");
        assertThat(listerStandIds(DEFAUT)).contains("STAND-PARTAGE");
    }

    @Test
    void supprimerLeGroupeParDefautEstRefuse() {
        creerGroupe("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when().delete("/api/groupes/" + DEFAUT)
                .then().statusCode(400);
    }

    @Test
    void supprimerLeGroupeCourantEstRefuse() {
        creerGroupe("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when().delete("/api/groupes/ANNEE-2026")
                .then().statusCode(400);
    }

    @Test
    void creerDeuxFoisLeMemeIdentifiantEstRefuse() {
        creerGroupe("ANNEE-2026", "Année 2026");

        given().contentType("application/json")
                .body("{\"id\":\"ANNEE-2026\",\"nom\":\"Doublon\"}")
                .when().post("/api/groupes")
                .then().statusCode(400);
    }
}
