package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The author of an ajustement is the server's to write, once. The screen used
 * to send the constant {@code ui}, and the upsert copied whatever came: the
 * author came from the client, and the last edit replaced the real one.
 *
 * <p>The test profile opens the API, so a request here carries no account and
 * creates an ajustement under none; what a server gesture names — the MCP
 * tool's {@code mcp}, the covoiturage's {@code collecte} — is what an edit must
 * leave in place.</p>
 */
@QuarkusTest
class ContrainteAdHocAuthorTest {

    @Inject
    ReferenceDataService referenceData;

    private Animateur premier;
    private Animateur second;

    private final List<String> creees = new ArrayList<>();

    @BeforeEach
    void createAnimateurs() {
        premier = referenceData.createAnimateur(new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
        second = referenceData.createAnimateur(new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
    }

    @AfterEach
    void cleanUp() {
        creees.forEach(referenceData::deleteContrainteAdHoc);
        referenceData.deleteAnimateur(premier.getId());
        referenceData.deleteAnimateur(second.getId());
    }

    @Test
    void anAuthorSentByTheClientIsIgnoredOnCreation() {
        JsonPath ecrite = post(body(null, "Ne pas les mettre ensemble", "pirate"));
        String id = ecrite.getString("contrainte.id");
        creees.add(id);

        assertThat(ecrite.getString("contrainte.creeParUtilisateurId")).isNotEqualTo("pirate");
        assertThat(authorOf(id)).isNotEqualTo("pirate");
    }

    @Test
    void anEditKeepsTheAuthorItWasCreatedUnder() {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(null, TypeContrainteAdHoc.INCOMPATIBILITE);
        contrainte.setAnimateursConcernes(new ArrayList<>(List.of(premier, second)));
        contrainte.setRaison("Posé par l'assistant");
        contrainte.setCreeParUtilisateurId("mcp");
        String id = referenceData.writeContrainteAdHoc(contrainte).contrainte().getId();
        creees.add(id);

        // Through the screen's route, with the constant it used to send...
        JsonPath ecrite = post(body(id, "Raison corrigée à l'écran", "ui"));
        assertThat(ecrite.getString("contrainte.raison")).isEqualTo("Raison corrigée à l'écran");
        assertThat(ecrite.getString("contrainte.creeParUtilisateurId")).isEqualTo("mcp");

        // ...and through server code that names another author.
        ContrainteAdHoc relue = referenceData.listContraintesAdHoc().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseThrow();
        relue.setRaison("Raison corrigée par un geste serveur");
        relue.setCreeParUtilisateurId("jour-j");
        assertThat(referenceData.writeContrainteAdHoc(relue).contrainte().getCreeParUtilisateurId())
                .isEqualTo("mcp");

        assertThat(authorOf(id)).isEqualTo("mcp");
    }

    private Map<String, Object> body(String id, String raison, String auteur) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("type", "INCOMPATIBILITE");
        body.put("animateursConcernes", List.of(Map.of("id", premier.getId()), Map.of("id", second.getId())));
        body.put("raison", raison);
        body.put("creeParUtilisateurId", auteur);
        return body;
    }

    private static JsonPath post(Map<String, Object> body) {
        return given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static String authorOf(String id) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("find { it.id == '" + id + "' }.creeParUtilisateurId");
    }
}
