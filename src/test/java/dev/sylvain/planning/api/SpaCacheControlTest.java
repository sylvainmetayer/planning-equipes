package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Cache policy of the frontend ({@link SpaCacheControlFilter}): the files
 * served under a stable name must be revalidated on every visit, or a
 * redeployment leaves returning browsers running the previous build — whose
 * lazily-loaded chunks no longer exist, so every navigation dies silently
 * (the mobile "dead menu" bug).
 *
 * <p>Quinoa is disabled under test, so the packaged frontend is absent. Two
 * fixtures in {@code src/test/resources/META-INF/resources/} stand in for the
 * files that matter — an {@code index.html} and an i18n catalog — and are
 * served by the very static-resources handler that serves the real ones in
 * production, whose {@code Cache-Control: public, immutable} is what these
 * assertions have to see overridden.</p>
 */
@QuarkusTest
class SpaCacheControlTest {

    /** The Angular build copies this folder across verbatim: no hashed names inside. */
    private static final Path PUBLIC_FOLDER = Path.of("src/main/webui/public");

    @Test
    void theShellIsRevalidatedOnEveryVisit() {
        given().when().get("/index.html")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("no-cache"));
    }

    /** Any HTML response, whatever its path: the trigger is the content type. */
    @Test
    void anyHtmlResponseIsCovered() {
        given().when().get("/q/swagger-ui")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("no-cache"));
    }

    /** The i18n catalogs share the stable-name problem: same revalidation. */
    @Test
    void translationCatalogsToo() {
        given().when().get("/i18n/messages.en.json")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("no-cache"));
    }

    /** API responses are not the frontend: the filter must not touch them. */
    @Test
    void apiResponsesAreLeftAlone() {
        given().when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .header("Cache-Control", blankOrNullString());
    }

    /**
     * The filter enumerates {@code public/} by hand — a path list cannot be
     * derived at runtime from a folder that only exists at build time. This is
     * what stops the list from rotting the day someone drops another logo in:
     * a file served under a name a redeployment will not change, yet cached
     * for a day as {@code immutable}, is exactly the bug this class is about.
     */
    @Test
    void noPublicFileEscapesTheRule() throws IOException {
        try (Stream<Path> tree = Files.walk(PUBLIC_FOLDER)) {
            List<String> served = tree.filter(Files::isRegularFile)
                    .map(file -> "/" + PUBLIC_FOLDER.relativize(file))
                    .toList();
            assertThat(served).isNotEmpty();
            assertThat(served).allSatisfy(path -> assertThat(SpaCacheControlFilter.hasStableName(path))
                    .as("%s is served under a stable name yet escapes the no-cache rule", path)
                    .isTrue());
        }
    }
}
