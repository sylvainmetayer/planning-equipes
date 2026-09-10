package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rate limit on the availability declarations (issue #291): the espace's first
 * write route is reachable from the Internet with a URL as its credential, so
 * a session must not be a licence to write at the pace of the network.
 *
 * <p>Two things are checked together, because neither alone is the guarantee:
 * the loop is <b>cut short</b> with a 429 carrying its {@code Retry-After},
 * and it still left <b>one row</b> in the database — the domain rule "one
 * pending declaration per animateur" bounds the volume, this ceiling bounds
 * the pace.</p>
 *
 * <p>Its own profile, like {@link CodeRequestLimiterTest}: the ceiling drops
 * to two so the test holds in three calls, and a profile is a separate
 * application, hence a counter no other test class has already spent.</p>
 */
@QuarkusTest
@TestProfile(DeclarationRateLimitTest.Profil.class)
class DeclarationRateLimitTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.espace.declaration.max-envois", "2", "planning.espace.declaration.fenetre", "PT10M");
        }
    }

    private static final String ANIMATEUR = "DEBIT-DECL";
    private static final String EMAIL = "debit-decl@example.org";

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        RestAssured.requestSpecification = null;
        removeFixture();
        Animateur animateur = new Animateur(ANIMATEUR, "Carla", "Roux", LocalDate.of(1991, 3, 3), false);
        animateur.setEmail(EMAIL);
        referenceData.createAnimateur(animateur);
        mailbox.clear();
        String session = EspaceSessions.open(mailbox, token(), EMAIL);
        RestAssured.requestSpecification =
                new RequestSpecBuilder().addCookie("planning-espace", session).build();
        window(true);
    }

    @AfterEach
    void cleanUp() {
        window(false);
        RestAssured.requestSpecification = null;
        removeFixture();
    }

    @Test
    void auDelaDuPlafondLaDeclarationEstRefuseeAvecUnDelai() {
        declare("premier envoi").then().statusCode(200);
        declare("correction").then().statusCode(200);

        declare("boucle").then().statusCode(429).header("Retry-After", notNullValue());

        // Whatever the pace, the animateur never held more than one proposal:
        // that is the domain rule, and it is what bounds the database.
        assertThat(pendingCount()).isEqualTo(1);
    }

    private io.restassured.response.Response declare(String commentaire) {
        return given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[],\"souhaits\":[],\"commentaire\":\"" + commentaire + "\"}")
                .when()
                .post("/api/espace-animateur/" + token() + "/disponibilites");
    }

    private static void window(boolean ouverte) {
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":" + ouverte + "}")
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200);
    }

    /** Only this fixture's rows: the suite shares one database with every other class. */
    private static long pendingCount() {
        List<String> statuts = given().when()
                .get("/api/disponibilites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("findAll { it.animateurId == '" + ANIMATEUR + "' }.statut", String.class);
        return statuts.stream().filter("EN_ATTENTE"::equals).count();
    }

    private String token() {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(ANIMATEUR))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }

    /** Leaves the shared database as it was found. */
    private void removeFixture() {
        referenceData.listAnimateurs().stream()
                .map(Animateur::getId)
                .filter(ANIMATEUR::equals)
                .forEach(referenceData::deleteAnimateur);
    }
}
