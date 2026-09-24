package dev.sylvain.planning.observability;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.health.Readiness;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * The probes an operator and the compose healthcheck rely on: liveness says the
 * process runs, readiness adds the database and the Flyway history, and
 * neither says anything a public caller should not read.
 */
@QuarkusTest
class HealthProbesTest {

    /** Loopback, port 1: refused at once, so the test does not wait on a timeout. */
    private static final String UNREACHABLE_HOST = "127.0.0.1";

    @Test
    void livenessIsUp() {
        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void readinessIsUpAndNamesTheMigrationsCheck() {
        given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem(FlywayMigrationsReadinessCheck.NAME));
    }

    /**
     * Quarkus's datasource check would copy the driver's message — host and
     * port included — into this public body the moment the database fails; the
     * Flyway check alone stands for connectivity.
     */
    @Test
    void theBuiltInDatasourceCheckStaysOff() {
        given().when()
                .get("/q/health/ready")
                .then()
                .body("checks.name", not(hasItem("Database connections health check")));
    }

    @Test
    void readinessRevealsNoConnectionDetail() {
        String body = given().when().get("/q/health/ready").then().extract().asString();
        assertThat(body)
                .doesNotContainIgnoringCase("jdbc:")
                .doesNotContain("5432")
                .doesNotContain("festival");
    }

    /**
     * The case the probes exist for: the database stops answering. Readiness
     * goes DOWN through the check's own catch — the history cannot be read —
     * and says nothing of why: no data, and none of the connection string the
     * driver puts in its message. Liveness stays UP, since restarting the
     * process would not bring the database back.
     */
    @Test
    void anUnreachableDatabaseMakesReadinessDownWithoutDetailWhileLivenessStaysUp() {
        var unreachable = new FlywayMigrationsReadinessCheck();
        unreachable.flyway = Flyway.configure()
                .dataSource("jdbc:postgresql://" + UNREACHABLE_HOST + ":1/nowhere", "nobody", "nothing")
                .connectRetries(0)
                .load();
        QuarkusMock.installMockForType(unreachable, FlywayMigrationsReadinessCheck.class, Readiness.Literal.INSTANCE);

        String body = given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(503)
                .body("status", equalTo("DOWN"))
                .body(
                        "checks.find { it.name == '%s' }.status".formatted(FlywayMigrationsReadinessCheck.NAME),
                        equalTo("DOWN"))
                .body(
                        "checks.find { it.name == '%s' }.data".formatted(FlywayMigrationsReadinessCheck.NAME),
                        nullValue())
                .extract()
                .asString();
        assertThat(body)
                .doesNotContainIgnoringCase("jdbc:")
                .doesNotContain(UNREACHABLE_HOST)
                .doesNotContain("nowhere")
                .doesNotContain("nobody");

        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }
}
