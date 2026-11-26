package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupedVariablesTest {

    private static SmallRyeConfig config(Map<String, String> env) {
        return new SmallRyeConfigBuilder()
                .withSources(new io.smallrye.config.common.MapBackedConfigSource("env", env, 300) {})
                .withInterceptors(new GroupedVariablesInterceptor())
                .withMapping(ConfigMentionsLegales.class)
                .withMapping(ConfigBranding.class)
                .build();
    }

    @Test
    void groupedKeysFeedTheMappings() {
        SmallRyeConfig config = config(Map.of(
                "LEGAL_MENTIONS", "editeur=Association Test, 1 rue X; contact=a@example.invalid;base-legale=Contrat",
                "LEGAL_ACCESSIBILITE", "etat=partielle",
                "BRANDING_IMAGES", "logo=logo.png;pdf-logo=classpath:/branding/a.png",
                "BRANDING_COLORS", "accent=#8b1e3f;pdf-pill=#eeeeee"));

        ConfigMentionsLegales legal = config.getConfigMapping(ConfigMentionsLegales.class);
        ConfigBranding branding = config.getConfigMapping(ConfigBranding.class);

        assertThat(legal.editeur()).contains("Association Test, 1 rue X");
        assertThat(legal.contact()).contains("a@example.invalid");
        assertThat(legal.donnees().baseLegale()).contains("Contrat");
        assertThat(legal.hebergeur()).isEmpty();
        assertThat(legal.accessibilite().etat()).contains("partielle");
        assertThat(branding.logoUrl()).contains("logo.png");
        assertThat(branding.pdf().logo()).contains("classpath:/branding/a.png");
        assertThat(branding.accentColor()).contains("#8b1e3f");
        assertThat(branding.pdf().palette().pill()).isEqualTo("#eeeeee");
        // Untouched key: the default of the mapping.
        assertThat(branding.pdf().palette().headline()).isEqualTo("#1f2933");
    }

    @Test
    void oldNamesAreStillRead() {
        SmallRyeConfig config = config(Map.of("LEGAL_HEBERGEUR", "Hébergeur SAS", "BRANDING_PDF_ACCENT", "#123456"));

        assertThat(config.getConfigMapping(ConfigMentionsLegales.class).hebergeur())
                .contains("Hébergeur SAS");
        assertThat(config.getConfigMapping(ConfigBranding.class).pdf().palette().accent())
                .isEqualTo("#123456");
    }

    @Test
    void theGroupedKeyWinsOverTheOldName() {
        SmallRyeConfig config = config(Map.of("LEGAL_MENTIONS", "contact=new@example.invalid", "LEGAL_CONTACT", "old"));

        assertThat(config.getConfigMapping(ConfigMentionsLegales.class).contact())
                .contains("new@example.invalid");
    }

    @Test
    void anEscapedSemicolonStaysInTheValue() {
        assertThat(GroupedVariables.parse("conservation=un an\\; puis suppression;contact=x"))
                .containsEntry("conservation", "un an; puis suppression")
                .containsEntry("contact", "x");
    }

    @Test
    void warningsNameOldVariablesUnknownKeysAndNeverValues() {
        String secret = java.util.UUID.randomUUID().toString();
        Map<String, String> env = Map.of("LEGAL_CONTACT", secret, "BRANDING_IMAGES", "logo=a;logoo=b;oups");

        assertThat(GroupedVariables.warnings(env::get))
                .anyMatch(l -> l.contains("LEGAL_CONTACT") && l.contains("LEGAL_MENTIONS") && !l.contains(secret))
                .anyMatch(l -> l.contains("logoo") && l.contains("n'existe pas"))
                .anyMatch(l -> l.contains("segment") && l.contains("clé=valeur"))
                .noneMatch(l -> l.contains("oups"));
        assertThat(GroupedVariables.warnings(Map.<String, String>of()::get)).isEmpty();
    }

    @Test
    void theParserKeepsEqualsSignsUnicodeAndTrimsSpaces() {
        assertThat(GroupedVariables.parse(
                        "  editeur = Bénévoles du Jeu — 1 rue des Pions ; base-legale=art. 6.1.b=contrat;;"))
                .containsEntry("editeur", "Bénévoles du Jeu — 1 rue des Pions")
                .containsEntry("base-legale", "art. 6.1.b=contrat")
                .hasSize(2);
        assertThat(GroupedVariables.parse("")).isEmpty();
    }

    @Test
    void anEmptyGroupedKeyFallsBackToTheOldName() {
        SmallRyeConfig config = config(Map.of("LEGAL_MENTIONS", "contact=", "LEGAL_CONTACT", "old@example.invalid"));

        assertThat(config.getConfigMapping(ConfigMentionsLegales.class).contact())
                .contains("old@example.invalid");
    }

    /**
     * An unknown key warns and is ignored, it does not refuse the boot; a
     * value cut by an unescaped semicolon shows up as one, and its text never
     * reaches the log.
     */
    @Test
    void aValueCutByAnUnescapedSemicolonWarnsWithoutPrintingIt() {
        String tail = "Rue " + java.util.UUID.randomUUID();
        Map<String, String> env = Map.of("LEGAL_MENTIONS", "editeur=Asso; " + tail + ";hébergeur=" + tail);

        assertThat(GroupedVariables.warnings(env::get))
                .hasSize(2)
                .allMatch(l -> l.startsWith("LEGAL_MENTIONS") && !l.contains(tail) && !l.contains("Rue"));
        assertThat(config(env).getConfigMapping(ConfigMentionsLegales.class).editeur())
                .contains("Asso");
    }
}
