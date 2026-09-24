package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds the Keycloak theme, the image that carries it, and the stacks and
 * workflow that run or publish that image.
 *
 * <p>The theme is only ever seen through the image ({@code
 * docker/keycloak/Dockerfile}): the dev stack builds it, production pulls it
 * from GHCR. A theme the realm does not name, a {@code brand/} directory the
 * image does not expose to both theme types, or a compose file that quietly
 * went back to the official image would each break the login page — or drop
 * the e-mail code step the flow names — without a single functional test
 * noticing: a browser suite asserts on fields and buttons, not on a logo.</p>
 *
 * <p>Container-free on purpose: the checks read files.</p>
 */
class KeycloakThemeStructuralTest {

    private static final Path THEME = Path.of("docker/keycloak/themes/planning");
    private static final Path DOCKERFILE = Path.of("docker/keycloak/Dockerfile");
    private static final Path EXTENSION = Path.of("keycloak/code-email");

    /** What a client mounts over {@code brand/}: the login page and the e-mails read exactly these. */
    private static final List<String> BRAND_FILES =
            List.of("logo.svg", "background.svg", "brand.css", "logo-email.png");

    @Test
    void theVersionedRealmNamesTheThemeForBothLoginAndEmail() {
        JsonNode realm = KeycloakConfigFiles.realm();

        assertThat(realm.path("loginTheme").asText()).isEqualTo("planning");
        assertThat(realm.path("emailTheme").asText()).isEqualTo("planning");
        assertThat(KeycloakConfigFiles.read(KeycloakConfigFiles.TERRAFORM))
                .as("the production realm gets the same theme by default")
                .contains("login_theme = var.login_theme")
                .contains("email_theme = var.email_theme");
    }

    @Test
    void theLoginThemeExtendsTheNativeOneRatherThanCopyingIt() throws IOException {
        String properties = Files.readString(THEME.resolve("login/theme.properties"));

        assertThat(properties).contains("parent=keycloak.v2");
        // Every page comes from the parent; only the footer hook is overridden.
        try (Stream<Path> templates = Files.list(THEME.resolve("login"))) {
            assertThat(templates.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".ftl")))
                    .as("templates copied from the parent would drift silently at each Keycloak upgrade")
                    .containsExactly("footer.ftl");
        }
        assertThat(properties)
                .as("the accent colour is read from the environment, for the login page and the e-mails alike")
                .contains("brandAccent=${env.KEYCLOAK_BRAND_ACCENT:}");
        assertThat(Files.readString(THEME.resolve("email/theme.properties")))
                .contains("parent=base")
                .contains("brandAccent=${env.KEYCLOAK_BRAND_ACCENT:}");
    }

    @Test
    void theAccentFromTheEnvironmentIsOnlyEverAColour() throws IOException {
        // An environment variable is not a place to inject CSS into a login
        // page from: both templates accept a hex colour and nothing else.
        String guard = "brandAccent?matches(\"^#[0-9a-fA-F]{3,8}$\")";

        assertThat(Files.readString(THEME.resolve("login/footer.ftl"))).contains(guard);
        assertThat(Files.readString(THEME.resolve("email/html/template.ftl"))).contains(guard);
    }

    @Test
    void theBrandDirectoryShipsEveryFileTheThemeReads() throws IOException {
        for (String file : BRAND_FILES) {
            assertThat(THEME.resolve("brand").resolve(file)).as(file).isRegularFile();
        }
        String css = Files.readString(THEME.resolve("login/resources/css/planning.css"));
        assertThat(css).contains("url('../brand/logo.svg')").contains("url('../brand/background.svg')");
        assertThat(Files.readString(THEME.resolve("login/theme.properties")))
                .as("brand.css is loaded last, so a client can override any rule of the theme")
                .containsPattern("styles=.*css/planning\\.css brand/brand\\.css");
        assertThat(Files.readString(THEME.resolve("email/html/template.ftl")))
                .as("e-mail clients drop SVG: the e-mail logo is the PNG")
                .contains("${url.resourcesUrl}/brand/logo-email.png");
    }

    @Test
    void theImageExposesOneBrandDirectoryToBothThemeTypes() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE);

        assertThat(dockerfile)
                .contains("COPY --chown=1000:0 docker/keycloak/themes/planning /opt/keycloak/themes/planning");
        // One mount point, two symlinks: a client cannot give the e-mails a
        // logo the login page does not have, or the reverse.
        assertThat(dockerfile).contains("ln -s ../../brand /opt/keycloak/themes/planning/login/resources/brand");
        assertThat(dockerfile).contains("ln -s ../../brand /opt/keycloak/themes/planning/email/resources/brand");
        assertThat(dockerfile)
                .as("the server never runs as root: the image drops back to the official unprivileged user")
                .containsPattern("(?m)^USER 1000\\s*$");
    }

    /**
     * The flow of both realm descriptions names {@code planning-code-email}:
     * the image must carry the JAR that declares it, built from the module
     * next door and against the Keycloak version the image runs.
     */
    @Test
    void theImageCarriesTheExtensionTheFlowNames() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE);
        String pom = Files.readString(EXTENSION.resolve("pom.xml"));

        assertThat(dockerfile).contains("/opt/keycloak/providers/planning-keycloak-code-email.jar");
        assertThat(pom).contains("<finalName>planning-keycloak-code-email</finalName>");
        assertThat(Files.readString(EXTENSION.resolve(
                        "src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory")))
                .contains("dev.sylvain.planning.keycloak.EmailCodeAuthenticatorFactory");

        String image = first(dockerfile, "FROM quay\\.io/keycloak/keycloak:([\\d.]+)");
        String spi = first(pom, "<keycloak\\.version>([\\d.]+)</keycloak\\.version>");
        assertThat(spi)
                .as("a provider compiled against another Keycloak line loads, then breaks on the first missing method")
                .isEqualTo(image);
        assertThat(first(pom, "<maven\\.compiler\\.release>(\\d+)<"))
                .as("Keycloak's JDK, not the application's: a class file for 25 does not load on 21")
                .isEqualTo(first(dockerfile, "FROM maven:[\\d.]+-eclipse-temurin-(\\d+)"));
    }

    /**
     * The extension stays out of the application's build: it is a plugin
     * loaded by another program, compiled for another JDK.
     */
    @Test
    void theExtensionIsAStandaloneModuleOutsideTheApplicationBuild() throws IOException {
        assertThat(Files.readString(Path.of("pom.xml"))).doesNotContain("<module>keycloak/code-email</module>");
        assertThat(Files.readString(EXTENSION.resolve("pom.xml"))).doesNotContain("<parent>");
    }

    @Test
    void everyStackRunsTheImageOfTheRepositoryNotTheOfficialOne() throws IOException {
        String dev = Files.readString(Path.of("docker-compose.yml"));
        String prod = Files.readString(Path.of("docker-compose.prod.yml"));
        String publish = Files.readString(Path.of(".github/workflows/docker-keycloak-ghcr.yml"));

        assertThat(dev).contains("dockerfile: docker/keycloak/Dockerfile");
        assertThat(dev).doesNotContain("image: quay.io/keycloak/keycloak");
        assertThat(dev)
                .as("brand/ is mounted from the repository in dev, so a logo change needs no rebuild")
                .contains("./docker/keycloak/themes/planning/brand:/opt/keycloak/themes/planning/brand:ro");
        assertThat(dev)
                .as("one URL from the browser and from the application container: same port inside and out")
                .contains("--http-port=8081")
                .contains("\"8081:8081\"")
                .contains("KC_HOSTNAME: http://keycloak:8081");

        assertThat(prod).contains("planning-equipes-keycloak}:${APP_VERSION");
        assertThat(prod)
                .as("with no client directory, a named volume seeded from the image takes the place of brand/")
                .contains("${KEYCLOAK_BRAND_DIR:-keycloak_brand}:/opt/keycloak/themes/planning/brand:ro");
        assertThat(prod)
                .as("Keycloak is optional in this stack, not in the application")
                .contains("profiles: [keycloak]");

        assertThat(publish).contains("IMAGE: ghcr.io/${{ github.repository_owner }}/planning-equipes-keycloak");
        assertThat(publish).contains("file: ./docker/keycloak/Dockerfile");
        assertThat(publish).as("signed like the application image").contains("cosign sign --yes");
    }

    private static String first(String text, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        assertThat(matcher.find()).as("no match for %s", regex).isTrue();
        return matcher.group(1);
    }
}
