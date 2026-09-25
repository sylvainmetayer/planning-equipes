package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The consignes of an edition (ADR 0043) through a scenario file: laid down
 * on the screen's own route, exported, imported into an edition that never
 * saw them — and read back whole, the créneau the consigne added included,
 * under the id the import gave it.
 *
 * <p>The date is far in the future, since a consigne is only laid on a day
 * to come; the import itself is bound by no such rule.</p>
 */
@QuarkusTest
class ImportScenarioConsignesTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String NOM_EDITION = "Import consignes";
    private static final String JOUR = "2033-07-08";

    private static final String BASE = """
            festival:
              dateDebut: 2033-07-08

            creneaux:
              - id: J1-MATIN
                date: 2033-07-08
                heureDebut: "09:00"
                heureFin: "12:00"
              - id: J1-AM
                date: 2033-07-08
                heureDebut: "14:00"
                heureFin: "18:00"

            stands:
              - id: STAND-A
                nom: Stand A
                typologiesProposees:
                  - STRATEGIE
                effectifMin: 1
                effectifMax: 2
                reserveMajeurs: false

            animateurs:
              - id: A1
                prenom: Alice
                nom: Referente
                dateNaissance: 2002-07-19
                manager: false
                competences:
                  STRATEGIE: REFERENT
                joursIndisponibles: []
            """;

    /** The landing edition of the running test, created by name: its id is generated (ADR 0050). */
    private String edition;

    @BeforeEach
    void createTheLandingEdition() {
        createEdition();
    }

    @AfterEach
    void deleteTheLandingEdition() {
        given().when().delete("/api/editions/" + edition);
    }

    private void createEdition() {
        edition = given().contentType("application/json")
                .body("{\"nom\":\"" + NOM_EDITION + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    /** The id the landing edition gave the stand the file called {@code code}: a file id is a local reference. */
    private String standId(String code) {
        String id = given().header(HEADER, edition)
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("find { it.code == '" + code + "' }.id");
        assertThat(id).as("stand of code %s", code).isNotNull();
        return id;
    }

    private void importFile(String yaml) {
        // The charset is spelled out: RestAssured sends a text/plain body as
        // ISO-8859-1 by default, and the motif carries accents.
        given().header(HEADER, edition)
                .contentType("text/plain; charset=UTF-8")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);
    }

    private JsonPath consignes() {
        return given().header(HEADER, edition)
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private JsonPath creneaux() {
        return given().header(HEADER, edition)
                .when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static Map<String, Object> repas() {
        Map<String, Object> repas = new HashMap<>();
        repas.put("soirDebut", "18:00");
        repas.put("soirFin", "22:00");
        repas.put("justification", "Repas pris pendant la bande fermée");
        return repas;
    }

    /** The screen's gesture: a preset, then a consigne made from it, with an evening the grid did not have. */
    private void layDownAConsigneOnTheScreen() {
        Map<String, Object> prereglage = new HashMap<>();
        prereglage.put("nom", "Plan canicule");
        prereglage.put("fermetureDebut", "12:00");
        prereglage.put("fermetureFin", "16:00");
        prereglage.put("motif", "Arrêté préfectoral canicule");
        prereglage.put("fenetres", List.of(Map.of("debut", "18:00", "fin", "20:00")));
        prereglage.put("repas", repas());
        given().header(HEADER, edition)
                .contentType("application/json")
                .body(prereglage)
                .when()
                .post("/api/consignes/prereglages")
                .then()
                .statusCode(200);

        Map<String, Object> ouverture = new HashMap<>();
        ouverture.put("standId", standId("STAND-A"));
        ouverture.put("debut", "18:00");
        ouverture.put("fin", "20:00");
        ouverture.put("effectif", 2);
        Map<String, Object> demande = new HashMap<>();
        demande.put("dates", List.of(JOUR));
        demande.put("fermetureDebut", "12:00");
        demande.put("fermetureFin", "16:00");
        demande.put("motif", "Arrêté préfectoral canicule");
        demande.put("prereglage", "Plan canicule");
        demande.put("fenetres", List.of(Map.of("debut", "18:00", "fin", "20:00")));
        demande.put("ouvertures", List.of(ouverture));
        demande.put("repas", repas());
        given().header(HEADER, edition)
                .contentType("application/json")
                .body(demande)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200)
                .body("[0].creneauxAAjouter", hasSize(1));
    }

    private String export() {
        return given().header(HEADER, edition)
                .when()
                .get("/api/planning/export-scenario")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    @Test
    void aConsigneLaidDownExportedAndImportedComesBackWhole() {
        importFile(BASE);
        layDownAConsigneOnTheScreen();
        String yaml = export();

        assertThat(yaml)
                .contains("consignes:")
                .contains("prereglagesConsigne:")
                .contains("prereglage: Plan canicule")
                .contains("creneauxAjoutes:")
                .contains("justification: Repas pris pendant la bande fermée");

        // A fresh edition, which never saw the consigne.
        given().when().delete("/api/editions/" + edition).then().statusCode(204);
        createEdition();
        assertThat(consignes().getList("consignes")).isEmpty();

        importFile(yaml);

        JsonPath grille = creneaux();
        assertThat(grille.getList("heureDebut")).containsExactlyInAnyOrder("09:00:00", "14:00:00", "18:00:00");
        Object idDuSoir =
                grille.getList("findAll { it.heureDebut == '18:00:00' }.id").getFirst();

        JsonPath etat = consignes();
        assertThat(etat.getList("consignes")).hasSize(1);
        assertTheConsigneCameBackWhole(etat, idDuSoir);

        assertThat(etat.getList("prereglages")).hasSize(1);
        assertThat(etat.getString("prereglages[0].nom")).isEqualTo("Plan canicule");
        assertThat(etat.getString("prereglages[0].repas.justification"))
                .isEqualTo("Repas pris pendant la bande fermée");
    }

    /** The one consigne of the round trip, field by field. */
    private void assertTheConsigneCameBackWhole(JsonPath etat, Object idDuSoir) {
        assertThat(etat.getString("consignes[0].date")).isEqualTo(JOUR);
        assertThat(etat.getString("consignes[0].fermetureDebut")).isEqualTo("12:00:00");
        assertThat(etat.getString("consignes[0].fermetureFin")).isEqualTo("16:00:00");
        assertThat(etat.getString("consignes[0].motif")).isEqualTo("Arrêté préfectoral canicule");
        assertThat(etat.getString("consignes[0].prereglage")).isEqualTo("Plan canicule");
        assertThat(etat.getList("consignes[0].fenetres")).hasSize(1);
        assertThat(etat.getList("consignes[0].ouvertures")).hasSize(1);
        assertThat(etat.getString("consignes[0].ouvertures[0].standId")).isEqualTo(standId("STAND-A"));
        assertThat(etat.getInt("consignes[0].ouvertures[0].effectif")).isEqualTo(2);
        assertThat(etat.getString("consignes[0].repas.soirDebut")).isEqualTo("18:00:00");
        assertThat(etat.getString("consignes[0].repas.justification")).isEqualTo("Repas pris pendant la bande fermée");
        // The added créneau, under the id the import gave it — not the file's.
        assertThat(etat.getList("consignes[0].creneauxAjoutes")).containsExactly(idDuSoir);
    }

    /**
     * The planning import keeps the créneaux a stand opens on, and a créneau
     * a consigne added belongs to nobody's usual hours: a stand whose rules
     * close it at 18h gives the evening no seat, and the import would drop it.
     * The consigne is what put it on the grid, so the consigne puts it back.
     */
    @Test
    void anAddedCreneauNoStandOpensOnIsRecreatedForTheConsigne() {
        String yaml = """
                festival:
                  dateDebut: 2033-07-08

                creneaux:
                  - id: J1-AM
                    date: 2033-07-08
                    heureDebut: "14:00"
                    heureFin: "18:00"
                  - id: J1-SOIR
                    date: 2033-07-08
                    heureDebut: "18:00"
                    heureFin: "20:00"

                stands:
                  - id: STAND-A
                    nom: Stand A
                    typologiesProposees:
                      - STRATEGIE
                    effectifMin: 1
                    effectifMax: 2
                    reserveMajeurs: false
                    horaires:
                      - mode: OUVERTURE
                        jours: TOUS
                        fenetres:
                          - heureDebut: "14:00"
                            heureFin: "18:00"

                animateurs:
                  - id: A1
                    prenom: Alice
                    nom: Referente
                    dateNaissance: 2002-07-19
                    manager: false
                    competences:
                      STRATEGIE: REFERENT
                    joursIndisponibles: []

                consignes:
                  - date: 2033-07-08
                    fermetureDebut: "16:00"
                    motif: Orage
                    ouvertures:
                      - standId: STAND-A
                        debut: "18:00"
                        fin: "20:00"
                    creneauxAjoutes:
                      - date: 2033-07-08
                        heureDebut: "18:00"
                        heureFin: "20:00"
                """;

        importFile(yaml);

        JsonPath grille = creneaux();
        assertThat(grille.getList("heureDebut")).containsExactlyInAnyOrder("14:00:00", "18:00:00");
        Object idDuSoir =
                grille.getList("findAll { it.heureDebut == '18:00:00' }.id").getFirst();
        JsonPath etat = consignes();
        assertThat(etat.getList("consignes")).hasSize(1);
        assertThat(etat.getList("consignes[0].creneauxAjoutes")).containsExactly(idDuSoir);
        assertThat(etat.getString("consignes[0].fermetureFin")).isNull();
        assertThat(etat.getString("consignes[0].repas")).isNull();
    }

    /** Present, the sections replace; absent, they leave the edition's own alone. */
    @Test
    void theSectionsReplaceWhenPresentAndLeaveAloneWhenAbsent() {
        importFile(BASE);
        layDownAConsigneOnTheScreen();

        importFile(BASE);
        given().header(HEADER, edition)
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes", hasSize(1))
                .body("prereglages", hasSize(1));

        importFile(BASE + "\nprereglagesConsigne: []\nconsignes: []\n");
        given().header(HEADER, edition)
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes", hasSize(0))
                .body("prereglages", hasSize(0));
    }

    /** An end written {@code 00:00} is « jusqu'à minuit », stored as the open end the tables know. */
    @Test
    void midnightAsAnEndInTheFileLandsAsAnOpenEnd() {
        importFile(BASE + """

                prereglagesConsigne:
                  - nom: Journée entière
                    fermetureDebut: "12:00"
                    fermetureFin: "00:00"
                    motif: Fermeture totale
                    fenetres:
                      - debut: "08:00"
                        fin: "10:00"

                consignes:
                  - date: 2033-07-08
                    fermetureDebut: "12:00"
                    fermetureFin: "16:00"
                    motif: Orage
                    fenetres:
                      - debut: "18:00"
                        fin: "00:00"
                    ouvertures:
                      - standId: STAND-A
                        debut: "18:00"
                        fin: "00:00"
                """);

        given().header(HEADER, edition)
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes[0].fenetres[0].fin", nullValue())
                .body("consignes[0].ouvertures[0].fin", nullValue())
                .body("prereglages[0].fermetureFin", nullValue());
    }

    /** The file is held to the screen's meal rules: both bounds of a window or none, and room for the break. */
    @Test
    void aMealBlockStatingHalfAWindowOrAWindowTooShortIsRefused() {
        String demiFenetre = BASE + """

                consignes:
                  - date: 2033-07-08
                    fermetureDebut: "12:00"
                    fermetureFin: "16:00"
                    motif: Orage
                    repas:
                      midiDebut: "12:00"
                      justification: repas pris pendant la fermeture
                """;
        given().header(HEADER, edition)
                .contentType("text/plain; charset=UTF-8")
                .body(demiFenetre)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400)
                .body("message", containsString("deux bornes"));

        String tropCourte = BASE + """

                consignes:
                  - date: 2033-07-08
                    fermetureDebut: "12:00"
                    fermetureFin: "16:00"
                    motif: Orage
                    repas:
                      soirDebut: "19:00"
                      soirFin: "19:30"
                      justification: repas pris pendant la fermeture
                """;
        given().header(HEADER, edition)
                .contentType("text/plain; charset=UTF-8")
                .body(tropCourte)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400)
                .body("message", containsString("plus courte que la coupure"));
        given().header(HEADER, edition).when().get("/api/consignes").then().body("consignes", hasSize(0));
    }

    /** A hand-written preset carries no id and a consigne on a past date is accepted: the import is not the screen. */
    @Test
    void aHandWrittenFileOnAPastDateIsImported() {
        importFile(BASE.replace("2033-07-08", "2020-07-08") + """

                prereglagesConsigne:
                  - nom: Plan canicule
                    fermetureDebut: "12:00"
                    fermetureFin: "18:00"
                    motif: Arrêté préfectoral

                consignes:
                  - date: 2020-07-08
                    fermetureDebut: "12:00"
                    fermetureFin: "14:00"
                    motif: Orage
                    prereglage: Plan canicule
                """);

        given().header(HEADER, edition)
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes", hasSize(1))
                .body("consignes[0].date", equalTo("2020-07-08"))
                .body("consignes[0].creneauxAjoutes", hasSize(0))
                .body("consignes[0].repas", nullValue())
                .body("prereglages", hasSize(1))
                .body("prereglages[0].nom", equalTo("Plan canicule"))
                .body("prereglages[0].id", is(org.hamcrest.Matchers.notNullValue()));
    }
}
