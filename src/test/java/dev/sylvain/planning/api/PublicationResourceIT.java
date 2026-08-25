package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * Publishing against the <b>packaged</b> application, which runs the real
 * {@code %prod} configuration — admin authentication included. Unlike
 * {@link PublicationResourceTest}, nothing is injected here: the fixture goes
 * in through the admin API, and everything is asserted through it.
 *
 * <p>Deliberately <b>no assertion on delivery</b>. The packaged profile talks
 * to a real SMTP host, which may or may not answer on the machine running this
 * test; whether a mail left is not what this test is about. What it pins down
 * is what does not depend on SMTP: the count is per person, the trace names
 * everyone the publication addressed, and a second publication with nothing to
 * announce is refused.</p>
 *
 * <p>Each test seeds <b>its own</b> people and seats. A published snapshot is
 * out of the admin import's reach on purpose (it is not a business referential
 * table), so it survives from one test to the next: re-seeding identical seats
 * would leave nothing to announce, and the test would assert on the previous
 * test's publication instead of its own.</p>
 */
@QuarkusIntegrationTest
class PublicationResourceIT {

    private static String cookieSession;

    /** Logs in once (default dev credentials, see {@code ADMIN_PASSWORD}). */
    @BeforeEach
    void ouvrirSessionAdmin() {
        if (cookieSession == null) {
            cookieSession = RestAssured.given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("j_username", "admin")
                    .formParam("j_password", "admin")
                    .redirects().follow(false)
                    .when().post("/j_security_check")
                    .then()
                    .extract().cookie("planning-session");
            assertThat(cookieSession).as("form login should issue the session cookie").isNotBlank();
        }
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-session", cookieSession)
                .build();
    }

    @AfterAll
    static void fermerSessionAdmin() {
        RestAssured.requestSpecification = null;
        cookieSession = null;
    }

    @Test
    void publierNommeLesPersonnesConcerneesPuisRetombeAZero() {
        semer("ITPA", 9501, "Compte");

        JsonPath avant = apercu();
        assertThat(avant.getInt("nombreConcernes")).isEqualTo(2);
        assertThat(avant.getList("destinataires.nomAffiche"))
                .contains("Alice Compte", "Bruno Compte");

        JsonPath rapport = given().contentType(ContentType.JSON)
                .when().post("/api/planning/publication")
                .then().statusCode(200).extract().jsonPath();
        assertThat(rapport.getString("publieLe")).isNotBlank();

        JsonPath apres = apercu();
        assertThat(apres.getInt("nombreConcernes")).isZero();
        assertThat(apres.getBoolean("jamaisPublie")).isFalse();
        assertThat(apres.getString("dernierePublicationLe")).isNotBlank();

        // Nothing new to announce: that is the point, not an error to work around.
        given().contentType(ContentType.JSON)
                .when().post("/api/planning/publication")
                .then()
                .statusCode(409)
                .body("message", containsString("Personne n'est concerné"));
    }

    @Test
    void laTraceNommeToutLeMondeYComprisLesInjoignables() {
        semer("ITPB", 9502, "Trace");

        given().contentType(ContentType.JSON)
                .when().post("/api/planning/publication")
                .then().statusCode(200);

        JsonPath trace = given().when().get("/api/planning/publication/destinataires")
                .then().statusCode(200).extract().jsonPath();
        assertThat(trace.getList("animateurId")).contains("ITPB-A", "ITPB-B");
        assertThat(trace.getList("envoyeLe", String.class)).allSatisfy(quand -> assertThat(quand).isNotBlank());
        // Bruno has no address: he stays in the trace, otherwise "told" and
        // "to be told" would read the same at the next preview.
        assertThat(trace.getList("statut", String.class)).contains("SANS_EMAIL");
    }

    /**
     * Two seats on one créneau, through the admin import: Alice carries an
     * address, Bruno deliberately none — the two outcomes a publication has to
     * tell apart.
     */
    private void semer(String prefixe, int creneauId, String nom) {
        String script = String.join("\n",
                "delete from poste_affectation where id like '" + prefixe + "-%';",
                "delete from poste_affectation where creneau_id = " + creneauId + ";",
                "delete from creneau where id = " + creneauId + ";",
                "delete from animateur where id like '" + prefixe + "-%';",
                "delete from stand where id like '" + prefixe + "-%';",
                "insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs)"
                        + " values ('DEFAUT', '" + prefixe + "-S1', 'Stand " + prefixe + " un', 1, 1, false);",
                "insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs)"
                        + " values ('DEFAUT', '" + prefixe + "-S2', 'Stand " + prefixe + " deux', 1, 1, false);",
                "insert into animateur (edition_id, id, prenom, nom, date_naissance, manager, email)"
                        + " values ('DEFAUT', '" + prefixe + "-A', 'Alice', '" + nom + "', '1990-01-01', false,"
                        + " '" + prefixe.toLowerCase() + "-alice@example.org');",
                "insert into animateur (edition_id, id, prenom, nom, date_naissance, manager)"
                        + " values ('DEFAUT', '" + prefixe + "-B', 'Bruno', '" + nom + "', '1992-02-02', false);",
                "insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin)"
                        + " values ('DEFAUT', " + creneauId + ", '2026-07-11', '10:00', '12:00');",
                "insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id)"
                        + " values ('DEFAUT', '" + prefixe + "-P1', '" + prefixe + "-S1', " + creneauId
                        + ", '" + prefixe + "-A');",
                "insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id)"
                        + " values ('DEFAUT', '" + prefixe + "-P2', '" + prefixe + "-S2', " + creneauId
                        + ", '" + prefixe + "-B');");
        given().contentType(ContentType.TEXT)
                .body(script)
                .when().post("/api/database/import")
                .then().statusCode(200);
    }

    private JsonPath apercu() {
        return given().when().get("/api/planning/publication")
                .then().statusCode(200).extract().jsonPath();
    }
}
