package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.mcp.McpPrompts.PromptExpose;
import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the prompts <b>say</b>, checked without a server.
 *
 * <p>{@link McpPromptsResourcesTest} needs the running application: it asks
 * the MCP registry whether a prompt is really announced, and reads the
 * resources. The guards here are about the text alone — a prompt is a
 * {@code String} built by reflection, so it needs nothing but the class — and
 * they are the ones that must hold wherever the suite runs.</p>
 *
 * <p>The rule they defend is the one a prompt can quietly lose: a text that
 * tells an assistant to call a tool which mails 150 people, without telling it
 * to ask first.</p>
 */
class McpPromptsWordingTest {

    private final McpPrompts prompts = new McpPrompts();

    /**
     * Derived from the tools themselves rather than from a second list here:
     * {@code openWorldHint} is exactly « ce tool sort de l'application », and
     * {@code McpAnnotationsStructurelleTest} already holds it to the enumerated
     * senders. A new mail-sending tool therefore lands in this guard by itself.
     */
    private static List<String> outilsSortants() throws Exception {
        List<String> sortants = OutilsMcp.all().stream()
                .filter(outil -> outil.getAnnotation(Tool.class).annotations().openWorldHint())
                .map(Method::getName)
                .toList();
        assertThat(sortants).as("outils qui sortent de l'application").isNotEmpty();
        return sortants;
    }

    @Test
    void unPromptQuiNommeUnOutilSortantDemandeLAccordAvant() throws Exception {
        List<String> sortants = outilsSortants();

        for (PromptExpose expose : prompts.catalogue()) {
            for (String outil : sortants) {
                if (expose.texte().contains(outil)) {
                    assertThat(expose.texte())
                            .as(
                                    "le prompt %s nomme %s, qui envoie des courriels : il doit exiger un"
                                            + " accord explicite avant l'appel",
                                    expose.nom(), outil)
                            .containsAnyOf("mon accord", "sans mon accord", "demande-moi");
                }
            }
        }
    }

    /** The one prompt whose whole subject is an outgoing send says so upfront. */
    @Test
    void lePromptDePublicationAnnonceQuIlEnvoieDesCourriels() {
        String texte = prompts.publier_le_planning(null).content().asText().text();

        assertThat(texte).contains("envoie des courriels");
        assertThat(texte.indexOf("envoie des courriels"))
                .as("l'avertissement doit précéder l'outil qu'il concerne")
                .isLessThan(texte.indexOf("publier_planning"));
    }

    /**
     * Every prompt cites tools, and a cited tool that does not exist is the
     * defect {@code McpToolNamesTest} was written for. Repeated here on the
     * <b>rendered</b> text: that test reads the source file, so a name built by
     * string concatenation would escape it.
     */
    @Test
    void chaqueOutilCiteParUnPromptExiste() throws Exception {
        List<String> connus = OutilsMcp.all().stream().map(Method::getName).toList();

        for (PromptExpose expose : prompts.catalogue()) {
            for (String mot : expose.texte().split("[^a-z0-9_]+")) {
                if (mot.contains("_") && !mot.endsWith("_")) {
                    assertThat(connus)
                            .as("le prompt %s cite %s, qui doit être un outil existant", expose.nom(), mot)
                            .contains(mot);
                }
            }
        }
    }

    /** Written by hand, in the order of a real event — from the empty grid to the swaps. */
    @Test
    void leCatalogueSuitLeCycleDeVieDUnEvenement() {
        assertThat(prompts.catalogue())
                .extracting(PromptExpose::nom)
                .containsExactly(
                        "saisir_les_horaires_des_stands",
                        "construire_la_grille_de_creneaux",
                        "traiter_les_declarations_de_disponibilite",
                        "savoir_ou_recruter_ou_former",
                        "verifier_avant_resolution",
                        "resoudre_sans_perdre_le_planning",
                        "diagnostiquer_contraintes_dures",
                        "verrouiller_ce_qui_tient",
                        "preparer_une_variante_de_repli",
                        "auditer_avant_diffusion",
                        "publier_le_planning",
                        "traiter_les_demandes_dechange",
                        "reprendre_apres_un_changement_tardif",
                        "tenir_le_jour_j");
    }

    @Test
    void chaqueNouveauPromptPorteLEditionQuOnLuiDonne() {
        assertThat(prompts.traiter_les_declarations_de_disponibilite("Canicule 2026")
                        .content()
                        .asText()
                        .text())
                .contains("Canicule 2026");
        assertThat(prompts.publier_le_planning("Canicule 2026")
                        .content()
                        .asText()
                        .text())
                .contains("Canicule 2026");
        assertThat(prompts.traiter_les_demandes_dechange("Canicule 2026")
                        .content()
                        .asText()
                        .text())
                .contains("Canicule 2026");
    }

    /**
     * The privacy rule is invisible to an assistant until a text states it:
     * the tools simply answer ids, and « pourquoi n'ai-je pas les noms ? » is a
     * question worth answering before it is asked.
     */
    @Test
    void lesPromptsQuiParlentDesAnimateursRappellentQuIlsNontQuUnId() {
        for (String nom : List.of(
                "traiter_les_declarations_de_disponibilite",
                "publier_le_planning",
                "traiter_les_demandes_dechange",
                "diagnostiquer_contraintes_dures")) {
            PromptExpose expose = prompts.catalogue().stream()
                    .filter(candidat -> candidat.nom().equals(nom))
                    .findFirst()
                    .orElseThrow();
            assertThat(expose.texte()).as("texte de %s", nom).contains("id");
        }
    }
}
