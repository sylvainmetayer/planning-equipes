package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DatabaseResourceTest {

    /**
     * The browser uploads the dump as {@code application/sql}: tell RestAssured
     * to encode that content type as plain text.
     */
    private static RequestSpecification sqlRequest(String script) {
        return given().config(RestAssured.config()
                        .encoderConfig(
                                EncoderConfig.encoderConfig().encodeContentTypeAs("application/sql", ContentType.TEXT)))
                .contentType("application/sql")
                .body(script);
    }

    @Test
    void exportedDumpCanBeReplayedAndRestoresTheDataset() {
        // Reset first for a deterministic baseline (no leftovers from another
        // test), then seed: reset alone empties the database and leaves
        // nothing to export (see PlanningResourceTest.resetEmptiesTheDatabase).
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        int animateurs = given().when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$")
                .size();
        assertThat(animateurs).isPositive();

        String dump = given().when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString(".sql"))
                .extract()
                .asString();
        assertThat(dump).contains("DELETE FROM animateur;").contains("INSERT INTO animateur (");

        // Replaying the dump on a wiped database restores the exact same rows.
        sqlRequest(dump)
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));

        given().when().get("/api/animateurs").then().statusCode(200).body("size()", equalTo(animateurs));
    }

    @Test
    void exportedDumpIncludesCreneauxSoForeignKeysReplay() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        String dump = given().when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(dump).contains("INSERT INTO creneau (");

        sqlRequest(dump)
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));
    }

    @Test
    void exportedDumpIncludesParametresAndSurvivesReplay() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"dureeHebdomadaireMaxMinutes\":2760,\"dureeHebdomadaireMaxMineurMinutes\":2100,"
                        + "\"dureePauseMinutes\":45,\"reposQuotidienMinimalMinutes\":660}")
                .when()
                .put("/api/parametres-legaux")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"dureeResolutionSecondes\":42}")
                .when()
                .put("/api/parametres-solveur")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"actif\":false}")
                .when()
                .put("/api/constraints/dureeHebdomadaireMax")
                .then()
                .statusCode(200);

        String dump = given().when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(dump)
                .contains("INSERT INTO parametres_legaux (")
                .contains("2760")
                .contains("INSERT INTO parametres_solveur (")
                .contains("42")
                .contains("INSERT INTO constraint_toggle (")
                .contains("dureeHebdomadaireMax");

        sqlRequest(dump)
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));

        given().when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("dureeHebdomadaireMaxMinutes", equalTo(2760));

        given().when()
                .get("/api/parametres-solveur")
                .then()
                .statusCode(200)
                .body("dureeResolutionSecondes", equalTo(42));
    }

    @Test
    void exportedDumpRestoresIdentitySequencesSoNewRowsDoNotCollide() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        String dump = given().when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        sqlRequest(dump)
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));

        // A new créneau created after the replay must get a fresh id, not one
        // that collides with a row the dump just re-inserted with an explicit
        // identity value (see resyncIdentitySequences).
        given().contentType(ContentType.JSON)
                .body("{\"date\":\"2099-01-01\",\"heureDebut\":\"09:00:00\",\"heureFin\":\"10:00:00\"}")
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);
    }

    @Test
    void importRejectsStatementsOutsideTheAllowedScope() {
        sqlRequest("DROP TABLE animateur;")
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("Only INSERT, DELETE and TRUNCATE"));

        sqlRequest("DELETE FROM flyway_schema_history;")
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("not allowed"));

        // A rejected script must not have touched the database.
        given().when().get("/api/animateurs").then().statusCode(200);
    }

    @Test
    void importRejectsAnEmptyScript() {
        sqlRequest("-- nothing to replay\n")
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("does not contain any statement"));
    }

    /**
     * A dump carries its own {@code edition} table, so replaying one can wipe
     * the edition every request was resolving to. The ids EditionContext caches
     * must therefore be dropped along with the rows, otherwise the first
     * request after the restore looks up an edition that no longer exists.
     */
    @Test
    void importResolvesTheRestoredDefaultEditionInsteadOfTheWipedOne() {
        // The whole database as this class found it, replayed at the end: this
        // test is the only one that drops the default edition, and recreating it
        // through the API would give it back its row without its typologies —
        // the referential the tests running next expect to find seeded.
        String etatInitial = exportDump();

        // Restored whatever happens below: every class running after this one
        // needs the database this one found. Letting an assertion escape
        // mid-flight leaves them all resolving to an edition that no longer
        // exists, and one failure here becomes forty elsewhere.
        // Every edition id is drawn by the application (ADR 0050): the default
        // one is read, never assumed.
        String defautInitial = defaultEdition();

        try {
            given().when().post("/api/planning/reset").then().statusCode(200);

            // A dump whose only edition is the restored one — the initial default
            // is nowhere in it.
            String restauree = createEdition("Édition restaurée");
            makeDefaultEdition(restauree);
            deleteEdition(defautInitial);
            String dump = exportDump();

            // Read on the edition table alone: kpi_historique also carries an
            // edition_id, and no foreign key ties the two, so measurements taken
            // before the deletion outlive it and the dump keeps naming the
            // initial default further down.
            List<String> editionRows = dump.lines()
                    .filter(line -> line.startsWith("INSERT INTO edition ("))
                    .toList();
            assertThat(editionRows).isNotEmpty().noneMatch(line -> line.contains("'" + defautInitial + "'"));

            // Back to a database that only knows another default edition, and a
            // request that caches it.
            String autre = createEdition("Édition par défaut");
            makeDefaultEdition(autre);
            deleteEdition(restauree);
            given().when().get("/api/editions/courant").then().statusCode(200).body("id", equalTo(autre));

            sqlRequest(dump).when().post("/api/database/import").then().statusCode(200);

            given().when().get("/api/editions/courant").then().statusCode(200).body("id", equalTo(restauree));
        } catch (Throwable inFlight) {
            // Not a finally: a restore that fails in turn would replace the
            // assertion that actually diagnoses the defect. It travels as a
            // suppressed exception instead.
            try {
                restoreDatabase(etatInitial);
            } catch (Throwable duringRestore) {
                inFlight.addSuppressed(duringRestore);
            }
            throw inFlight;
        }
        restoreDatabase(etatInitial);

        given().when().get("/api/editions/courant").then().statusCode(200).body("id", equalTo(defautInitial));
    }

    private static void restoreDatabase(String dump) {
        sqlRequest(dump).when().post("/api/database/import").then().statusCode(200);
    }

    private static String exportDump() {
        return given().when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    /** Creates an edition and answers the id the application drew for it. */
    private static String createEdition(String nom) {
        return given().contentType(ContentType.JSON)
                .body("{\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    /** The default edition of the test database, read rather than assumed. */
    private static String defaultEdition() {
        return given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("find { it.defaut == true }.id");
    }

    private static void makeDefaultEdition(String id) {
        given().when().put("/api/editions/" + id + "/defaut").then().statusCode(204);
    }

    private static void deleteEdition(String id) {
        given().when().delete("/api/editions/" + id).then().statusCode(204);
    }

    /**
     * The published plan and its recipients survive being deleted and restored.
     *
     * <p>Chosen among the nine tables this dump gained because it is the one
     * that exercises the most: {@code publication_destinataire} carries a
     * foreign key to {@code plan_snapshot}, so the insert order in
     * {@code TABLES} has to be right and the delete order — issued in reverse —
     * has to be right too. Both rows also have a {@code BIGSERIAL} id, so both
     * belong in {@code IDENTITY_TABLES}.</p>
     *
     * <p>Deleted through the API rather than through {@code /api/planning/reset}:
     * reset does not touch {@code plan_snapshot}, so a restore that changed
     * nothing would still have looked green.</p>
     */
    @Test
    void thePublishedPlanAndItsRecipientsSurviveARestore() {
        // Raw rows on purpose, with literal ids — but in the edition the
        // requests below resolve to, whose id is drawn (ADR 0050).
        String defaut = defaultEdition();
        sqlRequest("INSERT INTO plan_snapshot (edition_id, id, libelle, nombre_affectations, contenu) VALUES "
                        + "('" + defaut + "', 777, 'Plan du test', 3, '{\"postes\": []}');\n"
                        + "INSERT INTO publication_destinataire "
                        + "(edition_id, id, snapshot_id, animateur_id, nom_affiche, email, statut) VALUES "
                        + "('" + defaut
                        + "', 888, 777, 'ani-test', 'Camille Essai', 'camille@example.test', 'ENVOYE');")
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(200);

        try {
            given().when()
                    .get("/api/planning/snapshots")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == 777 }.libelle", equalTo("Plan du test"));

            String dump = exportDump();
            assertThat(dump).contains("INSERT INTO plan_snapshot (").contains("INSERT INTO publication_destinataire (");

            // The wipe the restore is supposed to undo: the delete cascades onto
            // the recipient, so both rows go.
            given().when().delete("/api/planning/snapshots/777").then().statusCode(204);
            given().when()
                    .get("/api/planning/snapshots")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == 777 }", nullValue());

            sqlRequest(dump).when().post("/api/database/import").then().statusCode(200);

            given().when()
                    .get("/api/planning/snapshots")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == 777 }.libelle", equalTo("Plan du test"));

            // The recipient has no read endpoint of its own; a second export is
            // the honest way to say the row is back in the database.
            assertThat(exportDump()).contains("camille@example.test");
        } finally {
            given().when().delete("/api/planning/snapshots/777").then().statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }

    /**
     * The nine tables the dump gained are all actually emitted.
     *
     * <p>A cheap assertion, and the one that would have caught the original
     * defect: a table absent from {@code TABLES} produces neither a
     * {@code DELETE FROM} nor an {@code INSERT}, so its name simply never
     * appears. {@code DatabaseDumpCoverageTest} guards the classification; this
     * guards the emission.</p>
     */
    @Test
    void theDumpNamesEveryTableItClaimsToCarry() {
        String dump = exportDump();
        assertThat(dump)
                .contains("DELETE FROM parametres_collecte")
                .contains("DELETE FROM parametres_echange")
                .contains("DELETE FROM parametres_notifications")
                .contains("DELETE FROM animateur_souhait")
                .contains("DELETE FROM declaration_disponibilite")
                .contains("DELETE FROM confirmation_planning")
                .contains("DELETE FROM notification_planifiee")
                .contains("DELETE FROM plan_snapshot")
                .contains("DELETE FROM publication_destinataire")
                .contains("DELETE FROM kpi_historique")
                // And the six that must not travel stay out, in both directions.
                .doesNotContain("horloge_jour_j")
                .doesNotContain("backup_settings")
                .doesNotContain("espace_acces")
                .doesNotContain("espace_session")
                .doesNotContain("solver_job")
                .doesNotContain("journal_action");
    }

    /**
     * The KPI history survives an export/import round trip.
     *
     * <p>It did not. {@code kpi_historique} was outside {@code TABLES}, so the
     * dump neither carried it nor deleted it — and its {@code edition_id} has no
     * foreign key, so the dump's {@code DELETE FROM edition} did not reach it
     * either. An operator restoring a dump kept whatever history the target
     * already had and lost the one they were restoring, silently, against a
     * class javadoc promising that "restoring it restores exactly what was
     * dumped".</p>
     *
     * <p>Written through the SQL import rather than a solve: the history is only
     * fed by {@code recordAfterSolve}, and this test is about the dump, not
     * about the solver. That the insert is accepted at all is half the fix —
     * the import's allow-list derives from the same constant.</p>
     */
    @Test
    void exportedDumpCarriesTheKpiHistoryBackAndForth() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        String defaut = defaultEdition();

        try {
            // An apostrophe on both sides of the row, deliberately: in the label
            // and inside the jsonb column, as a constraint name. kpi_historique
            // is the first jsonb column this dump has ever carried, so the
            // branch of literal() that quotes it had never run in anger — and a
            // quote reaching the import unescaped cuts the statement in half.
            sqlRequest("INSERT INTO kpi_historique (id, edition_id, edition_nom, kpi, cree_le) VALUES "
                            + "(4242, '" + defaut + "', 'Edition d''essai', "
                            + "'{\"heuresTotal\": 7.5, \"violationsParContrainte\": {\"repos d''une nuit\": 3}}', "
                            + "'2026-07-01T10:00:00Z');")
                    .when()
                    .post("/api/database/import")
                    .then()
                    .statusCode(200);

            assertTheMeasurementReadsBack();

            String dump = exportDump();
            assertThat(dump).contains("INSERT INTO kpi_historique (");

            // The wipe the restore is supposed to undo.
            given().when().post("/api/planning/reset").then().statusCode(200);

            sqlRequest(dump).when().post("/api/database/import").then().statusCode(200);

            assertTheMeasurementReadsBack();
        } finally {
            // Handed back clean: `POST /api/planning/reset` does not empty this
            // table — nothing does, short of the dump this test just taught to
            // carry it — so the row would follow every later class around. A
            // 404 is accepted so that a failure before the insert reports
            // itself rather than this cleanup.
            given().when().delete("/api/kpi/historique/4242").then().statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }

    /**
     * Read through the API rather than counted in the dump text: what matters is
     * that the measurement is still <em>usable</em> after the round trip, values
     * and escaping included, not that some bytes came back.
     */
    private static void assertTheMeasurementReadsBack() {
        JsonPath historique = given().when()
                .get("/api/kpi/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(historique.getString("find { it.id == 4242 }.editionNom")).isEqualTo("Edition d'essai");
        assertThat(historique.getDouble("find { it.id == 4242 }.kpi.heuresTotal"))
                .isEqualTo(7.5);
        // Read as a map rather than through a GPath expression: the key is
        // chosen for its apostrophe, which is exactly what GPath would choke on.
        Map<String, Object> violations = historique.getMap("find { it.id == 4242 }.kpi.violationsParContrainte");
        assertThat(violations).containsEntry("repos d'une nuit", 3);
    }
}
