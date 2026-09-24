package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Legal notice endpoint. Two things are being pinned down, and neither is
 * about the values themselves.
 *
 * <p>It must answer <b>without any credential</b>: a legal notice readable
 * only once logged in would miss the reader it exists for — someone deciding
 * whether to trust the site, or an animateur whose access link has expired.
 * And an unconfigured deployment must yield empty fields rather than a
 * half-invented publisher, so the page can say what is missing.</p>
 */
@QuarkusTest
class MentionsLegalesResourceTest {

    @Test
    void repondSansAuthentificationEtRendDesChampsVidesQuandRienNEstConfigure() {
        given().when()
                .get("/api/mentions-legales")
                .then()
                .statusCode(200)
                // The test profile configures none of them: blank, never a placeholder
                // the page would display as if it were a real publisher.
                .body("editeur", equalTo(""))
                .body("directeurPublication", equalTo(""))
                .body("hebergeur", equalTo(""))
                .body("contact", equalTo(""))
                .body("baseLegale", equalTo(""))
                .body("conservation", equalTo(""));
    }

    @Test
    void reportsWhichThirdPartyToolsRunSoThePageAnnouncesOnlyThose() {
        given().when()
                .get("/api/mentions-legales")
                .then()
                .statusCode(200)
                // Neither runs here: the Cloudflare token is blank outside %prod,
                // and %test pins observability.sentry.dsn empty so an exported
                // SENTRY_DSN cannot turn this assertion into a machine-dependent
                // failure. The privacy notice hangs its Cloudflare and Bugsink
                // paragraphs on these two booleans, and announcing a processing
                // that does not happen — a transfer outside the EU, for the first
                // one — costs the credit of every sentence on that page a reader
                // cannot check.
                .body("mesureAudience", equalTo(false))
                .body("suiviErreurs", equalTo(false));
    }

    /**
     * The deployment check reads this flag from the instance rather than from
     * an env file it may not see: the test profile declares nothing, so the
     * instance is one whose mandatory mentions are expected.
     */
    @Test
    void exposesWhetherTheInstanceDeclaredItselfADemo() {
        given().when().get("/api/mentions-legales").then().statusCode(200).body("demoInstance", equalTo(false));
    }

    /**
     * The accessibility statement is the deploying organisation's to fill: an
     * unconfigured one yields empty fields, and the page then says it is not
     * filled in rather than claiming a compliance nobody measured.
     */
    @Test
    void servesAnEmptyAccessibilityStatementWhenTheDeploymentStatesNone() {
        given().when()
                .get("/api/mentions-legales")
                .then()
                .statusCode(200)
                .body("accessibilite.etat", equalTo(""))
                .body("accessibilite.dateAudit", equalTo(""))
                .body("accessibilite.contenusNonAccessibles", equalTo(""))
                .body("accessibilite.signalement", equalTo(""));
    }
}
