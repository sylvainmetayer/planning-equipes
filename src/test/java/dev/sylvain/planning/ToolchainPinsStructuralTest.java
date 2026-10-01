package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The toolchain is pinned in several places, and {@code mise.toml} is the one
 * that counts: every other pin must agree with it.
 *
 * <p>Issue #392's C11 counted seven pins at three granularities — Java in
 * {@code mise.toml}, the pom, the workflows and two {@code FROM} lines; Node in
 * {@code mise.toml}, the workflows and {@code application.properties} (the one
 * that builds the bundle actually shipped); Maven in {@code mise.toml}, the
 * wrapper and a floating {@code maven:3.9} image — with nothing checking that
 * they say the same thing. Renovate reads some of them and not others, so a
 * bump lands in one file and the next contributor finds Java 25 here and Java
 * 26 there, each file sure of itself.</p>
 *
 * <p>Majors are compared, not patch versions: {@code mise.toml} says
 * {@code node = "24"} and lets mise pick the patch, while Quinoa needs an
 * exact {@code 24.18.0}. Agreeing on the major is what keeps a build local
 * and a build in CI from producing a different bundle; the patch is the
 * dependency bot's business — through the {@code customManager} of
 * {@code renovate.json} on that line, since no manager reads a
 * {@code .properties} on its own.</p>
 *
 * <p>Playwright is the one pin that must agree to the patch: a workflow
 * running in the {@code mcr.microsoft.com/playwright} image gets the browsers
 * of exactly one release, and a {@code @playwright/test} of another release
 * looks for a build the image does not have. Renovate grouping kept the two
 * together until a rule order or a release-age gap let one move alone; this
 * test is what makes that drift fail in the {@code test} job rather than as a
 * missing browser in the e2e one. The e2e job now runs on the host (it starts
 * Keycloak with {@code docker run}) and installs the browser of the locked
 * package itself, which cannot drift; the test then holds it to that.</p>
 */
class ToolchainPinsStructuralTest {

    private static final Path MISE = Path.of("mise.toml");
    private static final Path POM = Path.of("pom.xml");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path WRAPPER = Path.of(".mvn/wrapper/maven-wrapper.properties");
    private static final Path WORKFLOWS = Path.of(".github/workflows");
    private static final Path PACKAGE_JSON = Path.of("src/main/webui/package.json");
    private static final Path PACKAGE_LOCK = Path.of("src/main/webui/package-lock.json");
    private static final List<Path> COMPOSES =
            List.of(Path.of("docker-compose.yml"), Path.of("docker-compose.prod.yml"));

    @Test
    void everyPinAgreesWithMiseToml() throws IOException {
        String mise = read(MISE);
        String java = first(mise, "java = \"temurin-(\\d+)\"", "java in mise.toml");
        String node = first(mise, "node = \"(\\d+)\"", "node in mise.toml");
        String maven = first(mise, "maven = \"([\\d.]+)\"", "maven in mise.toml");

        List<String> ecarts = new ArrayList<>();

        // Java: the compiler, the two image stages, every workflow.
        expect(
                ecarts,
                "pom.xml maven.compiler.release",
                first(read(POM), "<maven.compiler.release>(\\d+)<", "pom"),
                java);
        String dockerfile = read(DOCKERFILE);
        expect(
                ecarts,
                "Dockerfile build stage",
                first(dockerfile, "FROM maven:[\\d.]+-eclipse-temurin-(\\d+)", "Dockerfile"),
                java);
        expect(
                ecarts,
                "Dockerfile runtime stage",
                first(dockerfile, "FROM eclipse-temurin:(\\d+)-jre", "Dockerfile"),
                java);
        forEachWorkflowPin(
                "java-version: *'?(\\d+)", (file, value) -> expect(ecarts, file + " java-version", value, java));

        // Node: the workflows, and the one Quinoa installs to build the shipped bundle.
        expect(
                ecarts,
                "application.properties quinoa node-version",
                first(read(PROPERTIES), "node-version=(\\d+)\\.", "application.properties"),
                node);
        forEachWorkflowPin(
                "node-version: *'?(\\d+)", (file, value) -> expect(ecarts, file + " node-version", value, node));

        // Maven: the wrapper pins the exact version, the build image only a prefix — it must be one of ours.
        expect(
                ecarts,
                "maven wrapper distributionUrl",
                first(read(WRAPPER), "apache-maven-([\\d.]+)-bin", "wrapper"),
                maven);
        String imageMaven = first(dockerfile, "FROM maven:([\\d.]+)-eclipse-temurin", "Dockerfile");
        if (!maven.startsWith(imageMaven)) {
            ecarts.add("Dockerfile maven image says " + imageMaven + ", mise.toml says " + maven);
        }

        // PostgreSQL: the tests, the deployments, the E2E service container and
        // the pg_dump client in the image.
        String postgresTests =
                first(read(PROPERTIES), "devservices\\.image-name=postgres:(\\d+)", "application.properties");
        for (Path compose : COMPOSES) {
            expect(
                    ecarts,
                    compose + " postgres image",
                    first(read(compose), "image: postgres:(\\d+)", compose.toString()),
                    postgresTests);
        }
        expect(
                ecarts,
                "Dockerfile postgresql-client",
                first(dockerfile, "postgresql-client-(\\d+)", "Dockerfile"),
                postgresTests);
        // The Playwright suite runs against a service container of its own,
        // backup and restore paths included — which depend on the server and
        // the pg_dump/psql of the image agreeing. It was the one pin left out.
        forEachWorkflowPin(
                "image: postgres:(\\d+)",
                (file, value) -> expect(ecarts, file + " postgres service", value, postgresTests));

        assertThat(ecarts).as("""
                        toolchain pins that disagree with mise.toml (java %s, node %s, maven %s). \
                        Bump them together — Renovate does not read every one of these files.""", java, node, maven).isEmpty();
    }

    @Test
    void playwrightImageFollowsTheLockedPackage() throws IOException {
        String declared = first(read(PACKAGE_JSON), "\"@playwright/test\": *\"([^\"]+)\"", "package.json");
        String locked = first(
                read(PACKAGE_LOCK),
                "\"node_modules/@playwright/test\": *\\{\\s*\"version\": *\"([^\"]+)\"",
                "package-lock.json");

        List<String> ecarts = new ArrayList<>();
        if (!declared.equals(locked)) {
            ecarts.add("package.json declares @playwright/test " + declared
                    + " (a range lets the lockfile move past the image), package-lock.json locks " + locked);
        }
        List<String> images = new ArrayList<>();
        forEachWorkflowPin("mcr\\.microsoft\\.com/playwright:v([\\d.]+)-", (file, value) -> {
            images.add(file);
            if (!value.equals(locked)) {
                ecarts.add(file + " runs the Playwright image " + value + ", package-lock.json locks " + locked);
            }
        });

        if (images.isEmpty()) {
            assertThat(read(WORKFLOWS.resolve("e2e-suite.yml")))
                    .as(
                            "no workflow runs the Playwright image: the e2e job must install the browser of the locked package")
                    .contains("npx playwright install");
        }
        assertThat(ecarts)
                .as("Playwright pins that disagree: bump @playwright/test and the e2e image together")
                .isEmpty();
    }

    private interface Pin {
        void found(String file, String value);
    }

    private static void forEachWorkflowPin(String regex, Pin pin) throws IOException {
        Pattern pattern = Pattern.compile(regex);
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                Matcher matcher = pattern.matcher(read(file));
                while (matcher.find()) {
                    pin.found(file.getFileName().toString(), matcher.group(1));
                }
            }
        }
    }

    private static void expect(List<String> ecarts, String where, String actual, String expected) {
        if (!expected.equals(actual)) {
            ecarts.add(where + " says " + actual + ", mise.toml says " + expected);
        }
    }

    private static String first(String text, String regex, String what) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("no match for " + regex + " in " + what + " — the pin moved, update this test");
        }
        return matcher.group(1);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
