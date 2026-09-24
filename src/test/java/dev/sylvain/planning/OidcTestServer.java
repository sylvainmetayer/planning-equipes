package dev.sylvain.planning;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;

/**
 * Starts the in-memory OIDC server for <b>every</b> {@code @QuarkusTest} of the
 * suite ({@code restrictToAnnotatedClass = false}): the {@code %test} profile
 * runs with Keycloak on, like production (ADR 0049), and needs an issuer to
 * discover. WireMock in-process — no container, so the suite stays Docker-free.
 *
 * <p>Not a test: a carrier for the annotation, which Quarkus finds by scanning
 * the test sources.</p>
 */
@QuarkusTestResource(value = OidcWiremockTestResource.class, restrictToAnnotatedClass = false)
public final class OidcTestServer {

    private OidcTestServer() {}
}
