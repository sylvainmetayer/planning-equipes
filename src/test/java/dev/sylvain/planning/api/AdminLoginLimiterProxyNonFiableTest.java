package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lockout of {@link AdminLoginLimiter} against a forged
 * {@code X-Forwarded-For} — the hole this class used to have.
 *
 * <p>It read the header itself and took its <b>first</b> element. A proxy
 * appends its own entry rather than replacing the header, so that first element
 * is whatever the client wrote: one forged address per attempt bought a fresh
 * counter, and the lock locked nothing.</p>
 *
 * <p>Going through {@code remoteAddress()} did <em>not</em> fix it, which is
 * worth stating because it looks like it should: Quarkus's {@code ForwardedParser}
 * also keeps the leftmost element, and with no {@code trusted-proxies} it trusts
 * every peer. The header must be walked from the <b>right</b>, stopping at the
 * first entry that is not a declared proxy — the address the last trusted hop
 * actually observed.</p>
 *
 * <p>This profile declares the loopback as the proxy, which is what the test
 * client connects from. Its sibling {@link AdminLoginLimiterTest} covers the
 * ordinary case, where the announced address is honoured.</p>
 */
@QuarkusTest
@TestProfile(AdminLoginLimiterProxyNonFiableTest.Profil.class)
class AdminLoginLimiterProxyNonFiableTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.auth.connexion.max-echecs",
                    "2",
                    "planning.auth.connexion.duree-blocage",
                    "PT15M",
                    // The address the test client actually connects from: it is
                    // the proxy, so its own entry is skipped and the one to its
                    // left is counted.
                    "planning.auth.connexion.proxys-fiables",
                    "127.0.0.1");
        }
    }

    /**
     * Three attempts, three different addresses <em>written by the client</em>,
     * one actually observed by the proxy: they count as one.
     *
     * <p>The header has the shape a real proxy produces — it appends the address
     * it sees after whatever the client sent. The last entry is therefore the
     * one that counts, and everything before it is attacker-chosen text. Before
     * the fix each was a counter of its own and the ceiling was never reached.</p>
     */
    @Test
    void aForgedLeftmostEntryBuysNoFreshCounter() {
        login("203.0.113.30, 198.51.100.7", "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login("203.0.113.31, 198.51.100.7", "mauvais").then().statusCode(anyOf(is(401), is(302)));

        login("203.0.113.32, 198.51.100.7", "mauvais")
                .then()
                .statusCode(429)
                .body("message", containsString("Trop de tentatives"));
    }

    /**
     * Lengthening the chain to look like several hops buys nothing either:
     * everything left of the last observed entry is attacker-chosen text.
     */
    @Test
    void aForgedProxyChainIsIgnoredUpToTheLastObservedHop() {
        login("10.0.0.1, 10.0.0.2, 198.51.100.8", "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login("172.16.0.1, 198.51.100.8", "mauvais").then().statusCode(anyOf(is(401), is(302)));

        login("198.51.100.8", "mauvais").then().statusCode(429);
    }

    /**
     * Two visitors behind the same proxy stay distinct: the lock pins one
     * address, not everybody behind the gateway.
     */
    @Test
    void twoVisitorsBehindTheSameProxyStayDistinct() {
        login("198.51.100.20", "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login("198.51.100.20", "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login("198.51.100.20", "mauvais").then().statusCode(429);

        login("198.51.100.21", "mauvais").then().statusCode(anyOf(is(401), is(302)));
    }

    /**
     * A header that is not an address must not reach the counter map as a null
     * key: Vert.x renders a non-IP host with a null {@code hostAddress()}, and
     * Squid emits {@code unknown} in this header. That answered 500 on
     * {@code /j_security_check}, which anyone could trigger.
     */
    @Test
    void aNonAddressForwardedForIsNotAnError() {
        login("unknown", "mauvais").then().statusCode(anyOf(is(401), is(302)));
        login("evil.example.com", "mauvais").then().statusCode(anyOf(is(401), is(302)));
    }

    private static Response login(String adresseAnnoncee, String password) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-For", adresseAnnoncee)
                .formParam("j_username", "admin")
                .formParam("j_password", password)
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check");
    }
}
