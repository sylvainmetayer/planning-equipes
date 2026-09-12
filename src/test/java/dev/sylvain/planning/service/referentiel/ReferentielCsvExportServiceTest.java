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
    private static final String SOURCE = "EXPORT-CSV-SRC";
    private static final String CIBLE = "EXPORT-CSV-DST";

    @BeforeEach
    void creerLesEditions() {
        for (String edition : new String[] {SOURCE, CIBLE}) {
            given().contentType("application/json")
                    .body("{\"id\":\"" + edition + "\",\"nom\":\"Export CSV " + edition + "\"}")
                    .when()
                    .post("/api/editions")
                    .then()
                    .statusCode(200);
        }
        // A complete referential, carrying what breaks a naive CSV: a
        // semicolon inside a name, accents, several values per cell.
        importer(
                SOURCE,
                "/api/typologies/import-csv",
                "id;libelle;ninja\nEXP-A;Jeux d'ambiance;\nEXP-B;Stratégie;oui\n");
        importer(
                SOURCE,
                "/api/emplacements/import-csv",
                "id;nom;latitude;longitude\nEXP-P;Pavillon;46.65;-0.24\nEXP-E;Esplanade;;\n");
        importer(
                SOURCE,
                "/api/stands/import-csv",
                "id;nom;typologies;effectifMin;effectifMax\n"
                        + "EXP-S1;\"Stand un; et demi\";EXP-A|EXP-B;2;3\n"
                        + "EXP-S2;Stand deux;EXP-B;1;1\n");
        given().header(HEADER, SOURCE)
                .contentType("application/json")
                .body("""
                        {"id":"EXP-A1","prenom":"Camille","nom":"Ferrand","dateNaissance":"1985-04-12",
                         "email":"camille@example.org","manager":true,
                         "competences":{"EXP-A":"REFERENT","EXP-B":"AUTONOME"},
                         "souhaits":["EXP-B"],"joursIndisponibles":["2026-09-01","2026-09-02"]}
                        """)
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void supprimerLesEditions() {
        given().when().delete("/api/editions/" + SOURCE);
        given().when().delete("/api/editions/" + CIBLE);
    }

    private static void importer(String edition, String chemin, String contenu) {
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"fileName\":\"f.csv\",\"content\":" + quote(contenu) + "}")
                .when()
                .post(chemin)
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
        assertThat(archive(SOURCE, "typologies=true&emplacements=true&stands=true&animateurs=true"))
                .containsOnlyKeys("typologies.csv", "emplacements.csv", "stands.csv", "animateurs.csv");

        given().header(HEADER, SOURCE)
                .when()
                .get("/api/reference-data/export-csv")
                .then()
                .statusCode(400)
                .body("message", containsString("au moins un"));
    }

    /**
     * The promise of the screen, held end to end: the archive of one edition
     * refills another, and not a single row is refused.
     */
    @Test
    void ceQuiSortSeReimporteSansUneSeuleCorrection() {
        Map<String, String> entrees = archive(SOURCE, "typologies=true&emplacements=true&stands=true&animateurs=true");

        // The BOM opens each entry for a spreadsheet; the import strips it itself.
        assertThat(entrees.get("typologies.csv")).startsWith("﻿");

        reimporter(entrees.get("typologies.csv"), "/api/typologies/import-csv", 2);
        reimporter(entrees.get("emplacements.csv"), "/api/emplacements/import-csv", 2);
        reimporter(entrees.get("stands.csv"), "/api/stands/import-csv", 2);

        // The animateur import demands an edition that has dates: without a
        // créneau, an imported off day would be neither shown nor kept, and the
        // import refuses it. That is why the créneaux are entered first, and why
        // the export screen says so.
        given().header(HEADER, CIBLE)
                .contentType("application/json")
                .body("{\"date\":\"2026-09-01\",\"heureDebut\":\"09:00\",\"heureFin\":\"12:00\"}")
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);
        given().header(HEADER, CIBLE)
                .contentType("application/json")
                .body("{\"date\":\"2026-09-02\",\"heureDebut\":\"09:00\",\"heureFin\":\"12:00\"}")
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);

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
        assertThat(copie.getString("find { it.id == 'EXP-A1' }.prenom")).isEqualTo("Camille");
        assertThat(copie.getString("find { it.id == 'EXP-A1' }.competences.EXP-A"))
                .isEqualTo("REFERENT");
        assertThat(copie.getList("find { it.id == 'EXP-A1' }.joursIndisponibles"))
                .hasSize(2);
        assertThat(copie.getBoolean("find { it.id == 'EXP-A1' }.manager")).isTrue();

        // The name carrying a semicolon crossed over whole.
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/stands")
                .then()
                .body("find { it.id == 'EXP-S1' }.nom", equalTo("Stand un; et demi"))
                .body("find { it.id == 'EXP-S1' }.typologiesProposees.size()", equalTo(2))
                .body("find { it.id == 'EXP-S1' }.effectifMax", equalTo(3));
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/typologies")
                .then()
                .body("find { it.id == 'EXP-B' }.ninja", equalTo(true));
        given().header(HEADER, CIBLE)
                .when()
                .get("/api/emplacements")
                .then()
                .body("find { it.id == 'EXP-P' }.latitude", equalTo(46.65f));
    }

    private static void reimporter(String contenu, String chemin, int attendus) {
        given().header(HEADER, CIBLE)
                .contentType("application/json")
                .body("{\"fileName\":\"f.csv\",\"content\":" + quote(contenu) + "}")
                .when()
                .post(chemin)
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("created", equalTo(attendus));
    }

    @Test
    void lesVolumesDisentCeQueChaqueReferentielEcrirait() {
        given().header(HEADER, SOURCE)
                .when()
                .get("/api/reference-data/export-csv/volumes")
                .then()
                .statusCode(200)
                .body("TYPOLOGIES", equalTo(2))
                .body("EMPLACEMENTS", equalTo(2))
                .body("STANDS", equalTo(2))
                .body("ANIMATEURS", equalTo(1));
    }
}
