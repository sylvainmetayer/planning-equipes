package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One armed edition on the whole instance: the scheduler serves every armed
 * edition, so two of them sharing a date wrote twice the same night to the
 * same people. Arming a second one is refused, and the refusal names the
 * edition to disarm first.
 */
@QuarkusTest
class ParametresNotificationsResourceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final List<String> EDITIONS = List.of("ARMEE-A", "ARMEE-B");

    @Inject
    DataSource dataSource;

    @BeforeEach
    void twoEditionsNobodyArmed() {
        disarm("DEFAUT");
        for (String id : EDITIONS) {
            given().contentType("application/json")
                    .body("{\"id\":\"" + id + "\",\"nom\":\"Édition " + id + "\"}")
                    .when()
                    .post("/api/editions");
        }
    }

    @AfterEach
    void dropTheEditions() {
        for (String id : EDITIONS) {
            disarm(id);
            given().when().delete("/api/editions/" + id);
        }
    }

    @Test
    void armingASecondEditionIsRefusedAndNamesTheArmedOne() {
        save("ARMEE-A", true).statusCode(200).body("actives", equalTo(true));

        save("ARMEE-B", true)
                .statusCode(409)
                .body("message", allOf(containsString("« Édition ARMEE-A »"), containsString("Désactivez")));
        given().header(HEADER, "ARMEE-B")
                .when()
                .get("/api/parametres-notifications")
                .then()
                .statusCode(200)
                .body("actives", equalTo(false));
    }

    @Test
    void theArmedEditionCanStillBeEditedAndOthersSavedDisarmed() {
        save("ARMEE-A", true).statusCode(200);

        save("ARMEE-A", true).statusCode(200);
        save("ARMEE-B", false).statusCode(200);
    }

    @Test
    void disarmingTheFirstLetsTheSecondBeArmed() {
        save("ARMEE-A", true).statusCode(200);
        save("ARMEE-A", false).statusCode(200);

        save("ARMEE-B", true).statusCode(200).body("actives", equalTo(true));
    }

    /**
     * The migration keeps the default edition armed when it is among the armed
     * ones, the first by id otherwise, and disarms the rest. Played in a
     * transaction rolled back at the end, the index dropped inside it so the
     * duplicate state the migration inherits can be written at all.
     */
    @Test
    void theMigrationKeepsOneArmedEditionAmongSeveral() throws Exception {
        String sql = migrationUpdate();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.execute("DROP INDEX idx_parametres_notifications_actives_unique");
                st.execute("UPDATE parametres_notifications SET actives = FALSE");
                armDirectly(st, "DEFAUT", "ARMEE-A", "ARMEE-B");
                st.execute(sql);
                assertThat(armed(st)).containsExactly("DEFAUT");

                armDirectly(st, "ARMEE-B", "ARMEE-A");
                st.execute("UPDATE parametres_notifications SET actives = FALSE WHERE edition_id = 'DEFAUT'");
                st.execute(sql);
                assertThat(armed(st)).containsExactly("ARMEE-A");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void armDirectly(Statement st, String... editions) throws SQLException {
        for (String edition : editions) {
            st.execute("INSERT INTO parametres_notifications (edition_id, actives) VALUES ('" + edition
                    + "', TRUE) ON CONFLICT (edition_id) DO UPDATE SET actives = TRUE");
        }
    }

    private static List<String> armed(Statement st) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (ResultSet rs =
                st.executeQuery("SELECT edition_id FROM parametres_notifications WHERE actives ORDER BY edition_id")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    /** The data half of V100: everything before the index it makes possible. */
    private static String migrationUpdate() throws IOException {
        try (InputStream in = ParametresNotificationsResourceTest.class.getResourceAsStream(
                "/db/migration/V100__une_seule_edition_armee.sql")) {
            String contenu = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return contenu.substring(0, contenu.indexOf("CREATE UNIQUE INDEX"));
        }
    }

    private static void disarm(String edition) {
        save(edition, false);
    }

    private static ValidatableResponse save(String edition, boolean actives) {
        return given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"actives\":" + actives
                        + ",\"heureRappelVeille\":\"18:00:00\",\"delaiRelanceHeures\":72,"
                        + "\"ancienneteEchangeJours\":3}")
                .when()
                .put("/api/parametres-notifications")
                .then();
    }
}
