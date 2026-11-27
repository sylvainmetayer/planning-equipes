package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.solve.SolverJobService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * {@code /mcp} as an OAuth 2.1 resource server (ADR 0097), against the
 * in-process OIDC server of {@code quarkus-test-oidc-server}: the tenant routing,
 * the audience check, the challenge, and what an account may do once its token
 * is accepted: an administrator everything, an organiser its editions only.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class OidcMcpTest {

    private static final String AUDIENCE = "planning-mcp";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    CompteService comptes;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    SolverJobService solverJobs;

    @Inject
    DataSource dataSource;

    private String bearer(RoleHabilitation role, String editionId) {
        String email = "mcp-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), role, editionId, null);
        return OidcTests.token(AUDIENCE, "s-" + UUID.randomUUID(), email, true);
    }

    /** Opens an MCP session with {@code authorization} and calls a tool; returns the tool result node. */
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

    @Test
    void theChallengeNamesTheAuthorizationServer() {
        given().when()
                .post("/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("resource_metadata"));
    }

    @Test
    void theProtectedResourceMetadataIsPublicAndNamesTheAuthorizationServer() throws Exception {
        String challenge =
                given().when().post("/mcp").then().statusCode(401).extract().header("WWW-Authenticate");
        Matcher url = Pattern.compile("resource_metadata=\"([^\"]+)\"").matcher(challenge);
        assertThat(url.find()).isTrue();

        String body = given().when()
                .get(URI.create(url.group(1)).getPath())
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(JSON.readTree(body).get("authorization_servers")).isNotEmpty();
    }

    @Test
    void aTokenIssuedForAnotherAudienceIsRefused() {
        // With a body, as a client sends one: the MCP extension's failure handler reads it, and
        // a bodyless request that fails authentication answers 500 upstream. Repeated: answered
        // off the event loop, the refusal raced the body and came back 500 one time in a few.
        String token = OidcTests.token("autre-application", "s", "a@example.org", true);
        for (int i = 0; i < 20; i++) {
            given().header("Authorization", token)
                    .contentType("application/json")
                    .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                    .when()
                    .post("/mcp")
                    .then()
                    .statusCode(401);
        }
    }

    @Test
    void aValidTokenOfAnUnknownAccountOpensNothing() {
        given().header(
                        "Authorization",
                        OidcTests.token(AUDIENCE, "s-" + UUID.randomUUID(), "inconnu@example.org", true))
                .when()
                .post("/mcp")
                .then()
                .statusCode(403);
    }

    /** Whatever roles a provider maps in its token: the application's own rights decide. */
    @Test
    void aProviderRoleOpensNothingWithoutAnAccount() {
        given().header(
                        "Authorization",
                        OidcTests.token(AUDIENCE, "s-" + UUID.randomUUID(), "x@example.org", true, "admin", "mcp"))
                .when()
                .post("/mcp")
                .then()
                .statusCode(403);
    }

    @Test
    void theApiKeyKeepsWorkingBesideOauth() {
        given().header("X-MCP-Api-Key", "test-mcp-key")
                .when()
                .post("/mcp")
                .then()
                .statusCode(not(401));
        given().header("X-MCP-Api-Key", "mauvaise-cle")
                .when()
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    void anAdministratorTokenCallsTools() throws Exception {
        JsonNode result = call(bearer(RoleHabilitation.ADMIN, null), "lister_editions", "{}");

        assertThat(isError(result)).as(result.toString()).isFalse();
    }

    @Test
    void anOrganiserSeesItsEditionsOnlyAndCannotDesignateAnother() throws Exception {
        String hors = editions.create(new Edition(null, "HORS-DROITS-" + UUID.randomUUID(), false, null))
                .getId();
        try {
            String organisateur = bearer(RoleHabilitation.ORGANISATEUR, "E1");

            JsonNode liste = call(organisateur, "lister_editions", "{}");
            assertThat(liste.toString()).contains("E1").doesNotContain(hors);

            JsonNode refus = call(organisateur, "lister_stands", "{\"edition\":\"" + hors + "\"}");
            assertThat(isError(refus)).isTrue();
            assertThat(refus.toString()).contains("Édition inconnue").doesNotContain("HORS-DROITS");

            JsonNode instance = call(organisateur, "creer_edition", "{\"nom\":\"X\"}");
            assertThat(isError(instance)).isTrue();
            assertThat(instance.toString()).contains("administrateurs");

            assertThat(isError(call(organisateur, "etat_sauvegardes", "{}"))).isTrue();
            assertThat(isError(call(organisateur, "consulter_sauvegardes", "{}")))
                    .isTrue();

            JsonNode permis = call(organisateur, "lister_stands", "{\"edition\":\"E1\"}");
            assertThat(isError(permis)).as(permis.toString()).isFalse();
        } finally {
            editions.delete(hors);
        }
    }

    /** A responsable de stand opens /api/responsable/* and nothing else: not the MCP transport. */
    @Test
    void aResponsableDeStandTokenOpensNothing() throws Exception {
        String edition = editions.create(new Edition(null, "RESP-MCP-" + UUID.randomUUID(), false, null))
                .getId();
        try {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES (?, 'S1', 'S', 1, 1)")) {
                ps.setString(1, edition);
                ps.executeUpdate();
            }
            String email = "resp-" + UUID.randomUUID() + "@example.org";
            Compte compte = comptes.create(email, null);
            comptes.grant(compte.id(), RoleHabilitation.RESPONSABLE_STAND, edition, null, List.of("S1"));

            given().header("Authorization", OidcTests.token(AUDIENCE, "s-" + UUID.randomUUID(), email, true))
                    .when()
                    .post("/mcp")
                    .then()
                    .statusCode(403);
        } finally {
            editions.delete(edition);
        }
    }

    /** The job registry is global: an organiser sees, stops and forgets the jobs of its editions only. */
    @Test
    void anOrganiserDoesNotReachTheJobsOfAnotherEdition() throws Exception {
        String autre = editions.create(new Edition(null, "JOBS-" + UUID.randomUUID(), false, null))
                .getId();
        try {
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> solverJobs.findActive().isEmpty());
            LocalDate jour = LocalDate.of(2030, 9, 3);
            PosteAffectation poste = new PosteAffectation(
                    "JOB-P1",
                    new Stand("JOB-S1", "Stand", Set.of(), 1, 1, false),
                    new Creneau(9801L, 1, jour, LocalTime.of(9, 0), LocalTime.of(12, 0)));
            PlanningEvenement probleme = new PlanningEvenement(
                    jour, List.of(new Animateur("JOB-A1", "P", "N", LocalDate.of(1990, 1, 1), false)), List.of(poste));
            String jobId = editionContext
                    .executeIn(autre, () -> solverJobs.submitSolve(probleme, 1L))
                    .getId();
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> solverJobs
                            .find(jobId)
                            .map(SolverJobService.SolverJob::isFinished)
                            .orElse(true));

            String organisateur = bearer(RoleHabilitation.ORGANISATEUR, "E1");
            assertThat(call(organisateur, "lister_jobs", "{}").toString()).doesNotContain(jobId);
            assertThat(isError(call(organisateur, "statut_solveur", "{\"jobId\":\"" + jobId + "\"}")))
                    .isTrue();
            assertThat(isError(call(organisateur, "arreter_solveur", "{\"jobId\":\"" + jobId + "\"}")))
                    .isTrue();
            assertThat(isError(call(organisateur, "supprimer_job", "{\"jobId\":\"" + jobId + "\"}")))
                    .isTrue();

            String admin = bearer(RoleHabilitation.ADMIN, null);
            assertThat(call(admin, "lister_jobs", "{}").toString()).contains(jobId);
            assertThat(isError(call(admin, "supprimer_job", "{\"jobId\":\"" + jobId + "\"}")))
                    .isFalse();
        } finally {
            editions.delete(autre);
        }
    }
}
