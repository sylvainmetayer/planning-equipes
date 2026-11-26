package dev.sylvain.planning.service.referentiel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The referentials written out, and — the promise the screen makes — read back
 * in without a single correction.
 *
 * <p>The round trip is the test that matters: a column the export renames, or
 * a multi-value cell it joins with the wrong character, would still produce a
 * plausible file and a broken import. So every export goes back through its
 * own tab here, and every row must be accepted.</p>
 */
@QuarkusTest
class ReferentielCsvExportServiceTest {

    private static final String HEADER = "X-Edition-Id";
    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String SOURCE;
    /** Drawn likewise: the edition the archive is replayed into. */
    private static String CIBLE;

    /** Every referential, as the screen sends them when nothing is unticked. */
    private static final String TOUT =
            "typologies=true&emplacements=true&stands=true" + "&creneaux=true&journeesTypes=true&animateurs=true";

    /**
     * A complete referential, carrying what breaks a naive CSV: a semicolon
     * inside a name, accents, several values per cell — and names a
     * spreadsheet would run as formulas. The grid holds a meal relay and a
     * night vacation ending past midnight.
     */
    private static final String REFERENTIEL_YAML = """
            schemaVersion: 6
            festival:
              dateDebut: 2026-09-01
            typologies:
              - id: EXP-A
                label: "Jeux d'ambiance"
              - id: EXP-B
                label: "Stratégie"
                polyvalent: true
            emplacements:
              - id: EXP-P
                nom: Pavillon
                latitude: 46.65
                longitude: -0.24
              - id: EXP-E
                nom: "-Esplanade"
            creneaux:
              - id: C1
                jour: 1
                date: 2026-09-01
                heureDebut: "09:00"
                heureFin: "12:00"
              - id: C2
                jour: 1
                date: 2026-09-01
                heureDebut: "12:00"
                heureFin: "13:00"
                couverturePause: true
              - id: C3
                jour: 1
                date: 2026-09-01
                heureDebut: "13:00"
                heureFin: "20:00"
              - id: C4
                jour: 2
                date: 2026-09-02
                heureDebut: "20:00"
                heureFin: "00:00"
            stands:
              - id: EXP-S1
                nom: "Stand un; et demi"
                typologiesProposees: [EXP-A, EXP-B]
                effectifMin: 2
                effectifMax: 3
              - id: EXP-S2
                nom: "=1+1"
                typologiesProposees: [EXP-B]
                effectifMin: 1
                effectifMax: 1
            animateurs: []
            gabarits:
              - nom: Jour normal
                vacations:
                  - { heureDebut: "09:00", heureFin: "12:00" }
                  - { heureDebut: "12:00", heureFin: "13:00", couverturePause: true }
                  - { heureDebut: "13:00", heureFin: "20:00" }
                dates: [2026-09-01]
              - nom: Nocturne
                vacations:
                  - { heureDebut: "20:00", heureFin: "00:00" }
                dates: [2026-09-02]
            """;

    @BeforeEach
    void createTheEditions() {
        SOURCE = createEdition("Export CSV source");
        CIBLE = createEdition("Export CSV cible");
        // A complete referential, carrying what breaks a naive CSV: a
        // semicolon inside a name, accents, several values per cell — and
        // names a spreadsheet would run as formulas.
        importer(SOURCE, REFERENTIEL_YAML);
        given().header(HEADER, SOURCE)
                .contentType("application/json")
                .body("""
                        {"prenom":"Camille","nom":"Ferrand","dateNaissance":"1985-04-12",
                         "email":"camille@example.org","manager":true,
                         "competences":{"EXP-A":"REFERENT","EXP-B":"AUTONOME"},
                         "souhaits":["EXP-B"],"joursIndisponibles":["2026-09-01","2026-09-02"]}
                        """)
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);
    }

    private static String createEdition(String nom) {
        return given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    @AfterEach
    void supprimerLesEditions() {
        given().header("X-Edition-Id", "E1").when().delete("/api/editions/" + SOURCE);
        given().header("X-Edition-Id", "E1").when().delete("/api/editions/" + CIBLE);
    }

    /** The workbook of an edition: the YAML file is the one way left to fill every referential at once. */
    private static void importer(String edition, String yaml) {
        given().header(HEADER, edition)
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);
    }

    private static String quote(String texte) {
        return "\"" + texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static Map<String, String> archive(String edition, String requete) {
        byte[] zip = given().header(HEADER, edition)
                .when()
                .get("/api/reference-data/export-csv?" + requete)
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        Map<String, String> entrees = new LinkedHashMap<>();
        try (ZipInputStream flux = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entree;
            while ((entree = flux.getNextEntry()) != null) {
                entrees.put(entree.getName(), new String(flux.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Archive illisible", e);
        }
        return entrees;
    }

    @Test
    void lArchiveNeTientQueCeQuiEstDemande() {
        assertThat(archive(SOURCE, "typologies=true&stands=true")).containsOnlyKeys("typologies.csv", "stands.csv");
        assertThat(archive(SOURCE, TOUT))
                .containsOnlyKeys(
                        "typologies.csv",
                        "emplacements.csv",
                        "stands.csv",
                        "creneaux.csv",
                        "journees-types.csv",
                        "animateurs.csv");

        given().header(HEADER, SOURCE)
                .when()
                .get("/api/reference-data/export-csv")
                .then()
                .statusCode(400)
                .body("message", containsString("au moins un"));
    }

    /**
     * The promise of the screen, held where a CSV is still read back: the
     * archive of one edition says what the workbook holds, and its animateurs
     * file refills another edition without a row refused. The other files are
     * read by a spreadsheet, not by the application: the YAML is their way in.
     */
    @Test
    void whatComesOutIsReimportedWithoutASingleCorrection() {
        Map<String, String> entrees = archive(SOURCE, TOUT);

        // The BOM opens each entry for a spreadsheet; the import strips it itself.
        assertThat(entrees.get("typologies.csv")).startsWith("﻿");
        // A name a spreadsheet would run goes out behind a quote, a negative
        // longitude stays the number it is.
        assertThat(entrees.get("stands.csv")).contains("EXP-S2;'=1+1;");
        assertThat(entrees.get("emplacements.csv"))
                .contains("EXP-E;'-Esplanade;")
                .contains(";-0.24\n");

        // The target gets the same workbook: the animateurs file names typologies
        // by code and demands an edition that has dates, so the off days are kept.
        importer(CIBLE, REFERENTIEL_YAML);

        given().header(HEADER, CIBLE)
                .contentType("application/json")
                .body("{\"fileName\":\"animateurs.csv\",\"content\":" + quote(entrees.get("animateurs.csv"))
                        + ",\"mapping\":null,\"replaceAnimateurs\":false,\"replaceJoursIndisponibles\":false}")
                .when()
                .post("/api/animateurs/import-csv")
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("created", equalTo(1));

        // And the fiche is the same on both sides, multi-valued cells included.
        var copie = given().header(HEADER, CIBLE)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        // The ids are the target's own: the fiche is found by its name, and
        // the compétence by the code its typologie carries on both sides.
        String typologieA = given().header(HEADER, CIBLE)
                .when()
                .get("/api/typologies")
                .then()
                .extract()
                .path("find { it.code == 'EXP-A' }.id");
        String camille = "find { it.prenom == 'Camille' }";
        assertThat(copie.getString(camille + ".nom")).isEqualTo("Ferrand");
        assertThat(copie.getString(camille + ".competences." + typologieA)).isEqualTo("REFERENT");
        assertThat(copie.getList(camille + ".joursIndisponibles")).hasSize(2);
        assertThat(copie.getBoolean(camille + ".manager")).isTrue();

        // The name carrying a semicolon crossed over whole.
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/stands")
                .then()
                .body("find { it.code == 'EXP-S1' }.nom", equalTo("Stand un; et demi"))
                .body("find { it.code == 'EXP-S1' }.typologiesProposees.size()", equalTo(2))
                .body("find { it.code == 'EXP-S1' }.effectifMax", equalTo(3))
                // The quote came off on the way back in.
                .body("find { it.code == 'EXP-S2' }.nom", equalTo("=1+1"));
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/typologies")
                .then()
                .body("find { it.code == 'EXP-B' }.polyvalent", equalTo(true));
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/emplacements")
                .then()
                .body("find { it.code == 'EXP-P' }.latitude", equalTo(46.65f))
                .body("find { it.code == 'EXP-P' }.longitude", equalTo(-0.24f))
                .body("find { it.code == 'EXP-E' }.nom", equalTo("-Esplanade"));

        // The grid crossed over whole, meal relay and night vacation included.
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/creneaux")
                .then()
                .body("$", org.hamcrest.Matchers.hasSize(4))
                .body("find { it.heureDebut == '12:00:00' }.couverturePause", equalTo(true))
                .body("find { it.date == '2026-09-02' }.heureFin", equalTo("00:00:00"));

        // And so did the day templates, their shift line and their dates.
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/journees-types")
                .then()
                .body("journeesTypes", org.hamcrest.Matchers.hasSize(2))
                .body("journeesTypes.find { it.nom == 'Jour normal' }.vacations", org.hamcrest.Matchers.hasSize(3))
                .body("journeesTypes.find { it.nom == 'Jour normal' }.vacations[1].couverturePause", equalTo(true))
                .body("calendrier", org.hamcrest.Matchers.hasSize(2));

        // Applying right after the round trip finds nothing to change: the
        // templates and the grid that came out of the same edition still agree.
        given().header(HEADER, CIBLE)
                .contentType("application/json")
                .when()
                .post("/api/journees-types/application/apercu")
                .then()
                .statusCode(200)
                .body("aucunChangement", equalTo(true));
    }

    @Test
    void theVolumesSayWhatEachReferentialWouldWrite() {
        given().header(HEADER, SOURCE)
                .when()
                .get("/api/reference-data/export-csv/volumes")
                .then()
                .statusCode(200)
                .body("TYPOLOGIES", equalTo(2))
                .body("EMPLACEMENTS", equalTo(2))
                .body("STANDS", equalTo(2))
                .body("CRENEAUX", equalTo(4))
                .body("JOURNEES_TYPES", equalTo(2))
                .body("ANIMATEURS", equalTo(1));
    }
}
