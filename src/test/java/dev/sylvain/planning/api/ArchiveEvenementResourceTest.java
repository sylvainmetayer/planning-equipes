package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/exports/archive-evenement}: the end-of-event archive. Every
 * file it carries is held to the export its own screen downloads, the scenario
 * it carries is imported back, and no espace token leaves in it — none of
 * which a unit test can say, since each of them is the meeting of two routes.
 */
@QuarkusTest
class ArchiveEvenementResourceTest {

    private static final String ARCHIVE = "/api/exports/archive-evenement";
    private static final String ALL_TEXT_PARTS = ARCHIVE + "?equite=true&heures=true&referentiels=true&scenario=true";
    private static final String EVERY_PART = ARCHIVE
            + "?pdfGlobal=true&equite=true&heures=true&referentiels=true&scenario=true&publication=true"
            + "&individuels=true";

    /** Saturday 11 July 2026, past for the real clock and the frozen one alike. */
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);

    private static final String ACCESS_TOKEN = "arch-access-0f3b9c1e-token";
    private static final String SUBSCRIPTION_TOKEN = "arch-abonnement-7d2a44e0-token";
    private static final String LANDING_EDITION = "ARCHIVE-REIMPORT";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    AgroalDataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        persistence.clearDatabase();
        Animateur alice = new Animateur("ARCH-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        alice.setEmail("alice.martin@example.org");
        Animateur bruno = new Animateur("ARCH-B", "Bruno", "Petit", LocalDate.of(2010, 2, 2), false);
        Stand stand = new Stand("ARCH-S1", "Stand des archives", Set.of("STRATEGIE"), 1, 2, false);
        Creneau matin = new Creneau(9701L, 1, SAMEDI, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau soir = new Creneau(9702L, 1, SAMEDI, LocalTime.of(18, 0), LocalTime.of(23, 0));
        PosteAffectation posteMatin = new PosteAffectation("ARCH-P1", stand, matin);
        posteMatin.setAnimateur(alice);
        PosteAffectation posteSoir = new PosteAffectation("ARCH-P2", stand, soir);
        posteSoir.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(SAMEDI, List.of(alice, bruno), List.of(posteMatin, posteSoir)));
        // Both credentials an animateur can hold, written straight into the
        // row: the archive must carry neither, whichever part reads the fiche.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE animateur SET access_token = ?, abonnement_token = ? WHERE edition_id = ? AND id = ?")) {
            update.setString(1, ACCESS_TOKEN);
            update.setString(2, SUBSCRIPTION_TOKEN);
            update.setString(3, "DEFAUT");
            update.setString(4, "ARCH-A");
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Two tests publish, and a publication outlives a reset: left behind, it
     * would tell the next class that this edition was published.
     */
    @AfterEach
    void forgetPublications() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete =
                        connection.prepareStatement("DELETE FROM plan_snapshot WHERE edition_id = ?")) {
            delete.setString(1, "DEFAUT");
            delete.executeUpdate();
        }
    }

    @Test
    void theArchiveHoldsExactlyTheChosenPartsAndTheManifest() throws IOException {
        Response response = given().when().get(ARCHIVE + "?pdfGlobal=true&equite=true&individuels=true&format=feuille");
        response.then().statusCode(200).contentType("application/zip");
        assertThat(response.header("Content-Disposition"))
                .matches("attachment; filename=\"archive-[a-z0-9-]+-\\d{4}-\\d{2}-\\d{2}\\.zip\"");

        Map<String, byte[]> entries = unzip(response.asByteArray());

        assertThat(entries.keySet())
                .containsExactly(
                        "LISEZMOI.txt",
                        "planning-global.pdf",
                        "equite.csv",
                        "plannings-individuels/Alice Martin.pdf",
                        "plannings-individuels/Alice Martin.ics",
                        "plannings-individuels/Bruno Petit.pdf",
                        "plannings-individuels/Bruno Petit.ics");
        assertThat(new String(entries.get("planning-global.pdf"), 0, 5, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        assertThat(text(entries.get("LISEZMOI.txt"))).contains("(format feuille)");
    }

    @Test
    void theTextPartsAreTheBytesTheirOwnExportsDownload() throws IOException {
        Map<String, byte[]> archive = unzip(given().when()
                .get(ALL_TEXT_PARTS)
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());

        assertThat(archive.get("equite.csv")).isEqualTo(download("/api/planning/equite/export"));

        // The Heures screen posts the persisted plan it read: the same round trip.
        String persisted = given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        byte[] heures = given().contentType(ContentType.JSON)
                .body(persisted)
                .when()
                .post("/api/planning/hours/export")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(archive.get("heures.csv")).isEqualTo(heures);

        Map<String, byte[]> referentiels = unzip(download("/api/reference-data/export-csv?typologies=true"
                + "&emplacements=true&stands=true&creneaux=true&journeesTypes=true&animateurs=true"));
        assertThat(referentiels).hasSize(6);
        referentiels.forEach((name, content) ->
                assertThat(archive.get("referentiels/" + name)).as(name).isEqualTo(content));

        assertThat(text(archive.get("scenario.yaml"))).isEqualTo(text(download("/api/planning/export-scenario")));
    }

    @Test
    void theScenarioOfTheArchiveImportsIntoAnEmptyEdition() throws IOException {
        byte[] scenario = unzip(given().when()
                        .get(ARCHIVE + "?scenario=true")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asByteArray())
                .get("scenario.yaml");
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"" + LANDING_EDITION + "\",\"nom\":\"Réimport d'archive\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
        try {
            given().header(EditionContext.HEADER, LANDING_EDITION)
                    .contentType("text/plain; charset=UTF-8")
                    .body(text(scenario))
                    .when()
                    .post("/api/reference-data/import-scenario-fichier")
                    .then()
                    .log()
                    .ifValidationFails()
                    .statusCode(200);
            given().header(EditionContext.HEADER, LANDING_EDITION)
                    .when()
                    .get("/api/animateurs")
                    .then()
                    .statusCode(200)
                    .body("size()", equalTo(2));

            // The stream is written for the edition of the request that asked
            // for it, not for the default one.
            String manifest = text(unzip(given().header(EditionContext.HEADER, LANDING_EDITION)
                            .when()
                            .get(ARCHIVE + "?referentiels=true")
                            .then()
                            .statusCode(200)
                            .extract()
                            .asByteArray())
                    .get("LISEZMOI.txt"));
            assertThat(manifest).contains("Édition : Réimport d'archive (" + LANDING_EDITION + ")");
        } finally {
            given().when().delete("/api/editions/" + LANDING_EDITION);
        }
    }

    @Test
    void noEspaceTokenLeavesInAnyPartOfTheArchive() throws IOException {
        // The control first: the individual documents of the Publication
        // screen do print the espace link, so a search for the token in these
        // bytes is one that can find it.
        String plan = given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        byte[] bundle = given().contentType(ContentType.JSON)
                .body(plan)
                .when()
                .post("/api/planning/export/bundle/all")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(unzip(bundle).values().stream().anyMatch(content -> contains(content, ACCESS_TOKEN)))
                .as("témoin : le PDF individuel du bundle porte le lien de l'espace")
                .isTrue();

        Map<String, byte[]> archive = unzip(
                given().when().get(EVERY_PART).then().statusCode(200).extract().asByteArray());

        assertThat(archive).containsKeys("publication.csv", "planning-global.pdf", "scenario.yaml");
        archive.forEach((name, content) -> {
            assertThat(contains(content, ACCESS_TOKEN)).as(name).isFalse();
            assertThat(contains(content, SUBSCRIPTION_TOKEN)).as(name).isFalse();
            assertThat(contains(content, "accessToken")).as(name).isFalse();
            assertThat(contains(content, "abonnementToken")).as(name).isFalse();
        });
    }

    @Test
    void askingForNothingIsA400ThatTheHistoryRecordsAsRefused() {
        given().when()
                .get(ARCHIVE)
                .then()
                .statusCode(400)
                .body("message", equalTo("Cochez au moins une partie à mettre dans l'archive."));

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.action == 'EXPORT_ARCHIVE_EVENEMENT' }.resultat", equalTo("REFUS"))
                .body("find { it.action == 'EXPORT_ARCHIVE_EVENEMENT' }.statut", equalTo(400));
    }

    @Test
    void theHistoryNamesThePartsTheArchiveCarried() {
        given().when().get(ARCHIVE + "?scenario=true&equite=true").then().statusCode(200);

        JsonPath historique = given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        Map<String, Object> ligne = historique.getMap("find { it.action == 'EXPORT_ARCHIVE_EVENEMENT' }");
        assertThat(ligne.get("resultat")).isEqualTo("SUCCES");
        assertThat(ligne.get("acteur")).isEqualTo("ADMIN");
        // The archive's order, not the query's.
        assertThat((List<?>) ligne.get("champs")).isEqualTo(List.of("equite", "scenario"));
    }

    @Test
    void theManifestSaysWhereThePlanComesFrom() throws IOException {
        String avant = manifest();
        assertThat(avant)
                .contains("Édition : ")
                .contains("Générée le : ")
                .containsPattern("Dernière résolution : \\d{2}/\\d{2}/\\d{4} à \\d{2}:\\d{2} .*, score ")
                .contains("Dernière publication : ")
                .contains("Journées relues : 0 sur 1")
                .contains("- LISEZMOI.txt : ")
                .contains("- referentiels/ : ")
                .contains("Responsable de traitement : ")
                .contains("Durée de conservation : ")
                .doesNotContain("planning-global.pdf");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(200);

        assertThat(manifest()).containsPattern("Dernière publication : \\d{2}/\\d{2}/\\d{4} à \\d{2}:\\d{2}");
    }

    @Test
    void theAvailabilityGreysOutWhatWouldComeOutEmpty() throws IOException {
        given().when()
                .get(ARCHIVE + "/disponibilite")
                .then()
                .statusCode(200)
                .body("planResolu", equalTo(true))
                .body("resolutionEnCours", equalTo(false));
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(200);
        given().when().get(ARCHIVE + "/disponibilite").then().statusCode(200).body("publie", equalTo(true));

        persistence.clearDatabase();

        given().when().get(ARCHIVE + "/disponibilite").then().statusCode(200).body("planResolu", equalTo(false));
        assertThat(text(unzip(given().when()
                                .get(ARCHIVE + "?equite=true")
                                .then()
                                .statusCode(200)
                                .extract()
                                .asByteArray())
                        .get("LISEZMOI.txt")))
                .contains("Aucun plan résolu");
    }

    /* ------------------------------------------------------------------------ */

    private String manifest() throws IOException {
        return text(unzip(given().when()
                        .get(ARCHIVE + "?referentiels=true")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asByteArray())
                .get("LISEZMOI.txt"));
    }

    private static byte[] download(String path) {
        return given().when().get(path).then().statusCode(200).extract().asByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.put(entry.getName(), input.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    private static boolean contains(byte[] content, String needle) {
        return new String(content, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
