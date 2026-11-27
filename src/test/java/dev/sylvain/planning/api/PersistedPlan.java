package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The persisted plan as the screens see it: {@code GET /api/planning/persisted}
 * sends each stand, timeslot and animateur once and the seats by id, and the
 * frontend gives every seat its objects back ({@code expandCompactPlanning}).
 * The tests read seats by {@code stand.id} or {@code animateur.id} the same way.
 */
final class PersistedPlan {

    private PersistedPlan() {}

    /** The plan of {@code edition}, every seat carrying its stand, timeslot and animateur. */
    static JsonPath read(String edition) {
        return JsonPath.from(json(edition));
    }

    /** Same, as the JSON body an export route takes. */
    static String json(String edition) {
        Map<String, Object> compact = given().header("X-Edition-Id", edition)
                .when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");
        try {
            // Plain maps of strings and numbers: no module of the application's mapper is needed.
            return new ObjectMapper().writeValueAsString(expand(compact));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The full form rebuilt from the compact one, as the frontend does. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> expand(Map<String, Object> compact) {
        Map<String, Object> full = new HashMap<>(compact);
        Map<Object, Object> stands = byId((List<Map<String, Object>>) full.remove("stands"));
        Map<Object, Object> creneaux = byId((List<Map<String, Object>>) full.remove("creneaux"));
        Map<Object, Object> animateurs = byId((List<Map<String, Object>>) compact.get("animateurs"));
        List<Map<String, Object>> postes = new ArrayList<>();
        for (Map<String, Object> seat : (List<Map<String, Object>>) compact.get("postes")) {
            Map<String, Object> poste = new HashMap<>(seat);
            poste.put("stand", stands.get(poste.remove("standId")));
            poste.put("creneau", creneaux.get(poste.remove("creneauId")));
            poste.put("animateur", animateurs.get(poste.remove("animateurId")));
            postes.add(poste);
        }
        full.put("postes", postes);
        return full;
    }

    private static Map<Object, Object> byId(List<Map<String, Object>> rows) {
        Map<Object, Object> index = new HashMap<>();
        rows.forEach(row -> index.put(row.get("id"), row));
        return index;
    }
}
