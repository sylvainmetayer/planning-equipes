package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A lecteur's token on {@code /mcp} (ADR 0099): the tools that read answer, in
 * its editions only; every other one is refused as a business refusal that
 * names nobody — the writes, the computations, the whole instance.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class OidcMcpLecteurTest {

    private static final String AUDIENCE = "planning-mcp";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    CompteService comptes;

    @Inject
    EditionService editions;

    private String bearer(Compte compte, String email) {
        return OidcTests.token(AUDIENCE, "s-" + UUID.randomUUID(), email, true);
    }

    private JsonNode call(String authorization, String tool, String arguments) throws Exception {
        Response init = post(authorization, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""");
        assertThat(init.statusCode()).as("initialize").isEqualTo(200);
        String session = init.header("Mcp-Session-Id");
        post(authorization, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        Response answer = post(
                authorization,
                session,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + tool
                        + "\",\"arguments\":" + arguments + "}}");
        assertThat(answer.statusCode()).as("tools/call").isEqualTo(200);
        String body = answer.asString();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                body = line.substring(5).trim();
            }
        }
        return JSON.readTree(body).get("result");
    }

    private static Response post(String authorization, String session, String body) {
        var request = given().header("Authorization", authorization)
                .header("Accept", "application/json, text/event-stream")
                .contentType("application/json");
        if (session != null) {
            request = request.header("Mcp-Session-Id", session);
        }
        return request.body(body).post("/mcp");
    }

    private static boolean isError(JsonNode result) {
        return result.has("isError") && result.get("isError").asBoolean();
    }

    private static final String REFUS_ECRITURE = "sans pouvoir la modifier";

    @Test
    void aLecteurReadsItsEditionsAndNothingElseGetsThrough() throws Exception {
        String hors = editions.create(new Edition(null, "HORS-DROITS-" + UUID.randomUUID(), false, null))
                .getId();
        try {
            String email = "lecteur-" + UUID.randomUUID() + "@example.org";
            Compte compte = comptes.create(email, null);
            comptes.grant(compte.id(), RoleHabilitation.LECTEUR, "E1", null);
            String lecteur = bearer(compte, email);

            JsonNode liste = call(lecteur, "lister_editions", "{}");
            assertThat(liste.toString()).contains("E1").doesNotContain(hors);
            JsonNode lu = call(lecteur, "lister_stands", "{\"edition\":\"E1\"}");
            assertThat(isError(lu)).as(lu.toString()).isFalse();

            JsonNode horsDroits = call(lecteur, "lister_stands", "{\"edition\":\"" + hors + "\"}");
            assertThat(isError(horsDroits)).isTrue();
            assertThat(horsDroits.toString()).doesNotContain("HORS-DROITS");

            // A write, a computation, a tool with no edition, an instance tool: all refused.
            JsonNode ecriture = call(lecteur, "supprimer_creneau", "{\"id\":1,\"edition\":\"E1\"}");
            assertThat(isError(ecriture)).isTrue();
            assertThat(ecriture.toString()).contains(REFUS_ECRITURE);
            JsonNode calcul = call(lecteur, "lancer_solveur", "{\"edition\":\"E1\"}");
            assertThat(isError(calcul)).isTrue();
            assertThat(calcul.toString()).contains(REFUS_ECRITURE);
            // A read-only tool that computes (@Computes): refused as a write.
            JsonNode simulation = call(lecteur, "diagnostiquer_plan", "{\"edition\":\"E1\"}");
            assertThat(isError(simulation)).isTrue();
            assertThat(simulation.toString()).contains(REFUS_ECRITURE);
            JsonNode sansEdition = call(lecteur, "supprimer_job", "{\"jobId\":\"x\"}");
            assertThat(isError(sansEdition)).isTrue();
            assertThat(sansEdition.toString()).contains(REFUS_ECRITURE);
            JsonNode instance = call(lecteur, "creer_edition", "{\"nom\":\"X\"}");
            assertThat(isError(instance)).isTrue();
            assertThat(instance.toString()).contains("administrateurs");
        } finally {
            editions.delete(hors);
        }
    }

    /** Lecteur of E1, organiser of another edition: the refusal stops at the edition it reads. */
    @Test
    void theRefusalStopsWhereTheAccountIsAnOrganiser() throws Exception {
        String autre = editions.create(new Edition(null, "MIXTE-" + UUID.randomUUID(), false, null))
                .getId();
        try {
            String email = "mixte-" + UUID.randomUUID() + "@example.org";
            Compte compte = comptes.create(email, null);
            comptes.grant(compte.id(), RoleHabilitation.LECTEUR, "E1", null);
            comptes.grant(compte.id(), RoleHabilitation.ORGANISATEUR, autre, null);
            String bearer = bearer(compte, email);

            assertThat(call(bearer, "supprimer_creneau", "{\"id\":1,\"edition\":\"E1\"}")
                            .toString())
                    .contains(REFUS_ECRITURE);
            assertThat(call(bearer, "supprimer_creneau", "{\"id\":1,\"edition\":\"" + autre + "\"}")
                            .toString())
                    .doesNotContain(REFUS_ECRITURE);
        } finally {
            editions.delete(autre);
        }
    }
}
