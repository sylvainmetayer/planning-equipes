package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An instance that switches drag and drop on gets it on {@code /api/config}:
 * the key read by {@code ConfigAdmin}, the resource and the JSON the frontend
 * reads, end to end. {@code ConfigAdminTest} covers the default and the
 * variable without booting; this is the half only a running server can show.
 */
@QuarkusTest
@TestProfile(ConfigResourceDragDropTest.DragDropOn.class)
class ConfigResourceDragDropTest {

    /** What {@code GLISSER_DEPOSER_ACTIF=true} resolves to. */
    public static class DragDropOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.admin.glisser-deposer", "true");
        }
    }

    @Test
    void anInstanceThatSwitchesItOnServesDragDropEnabled() {
        given().when().get("/api/config").then().statusCode(200).body("dragDropEnabled", equalTo(true));
    }
}
