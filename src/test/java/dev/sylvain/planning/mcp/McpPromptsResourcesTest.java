package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.mcp.McpPrompts.PromptExpose;
import dev.sylvain.planning.solver.ConstraintCatalog;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptManager;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Prompts and resources are text an assistant acts on, so what has to hold is
 * that the text says the right thing: the edition reaches it, the catalogue is
 * the solver's own, and no name of a person can travel in either.
 *
 * <p>What is checked here is what needs the <b>running server</b>: that a
 * declared prompt is really announced by the registry, and that a resource
 * answers the URI it advertises. The guards over the prompt text alone — their
 * display order, the tools they cite, the consent they must ask for before a
 * tool that mails 150 people — live in {@link McpPromptsWordingTest}, which
 * needs no container and therefore runs everywhere.</p>
 */
@QuarkusTest
class McpPromptsResourcesTest {

    @Inject
    McpPrompts prompts;

    @Inject
    McpResources resources;

    @Inject
    PromptManager promptManager;

    @Inject
    ResourceManager resourceManager;

    /**
     * The catalogue drives the MCP page, so a prompt missing from it would be
     * announced over MCP and invisible in the interface — and one listed but
     * no longer declared would break the page at runtime. Both directions are
     * checked, since the order is written by hand.
     */
    @Test
    void leCatalogueCouvreExactementLesPromptsDeclares() {
        List<String> declares = Arrays.stream(McpPrompts.class.getDeclaredMethods())
                .filter(methode -> methode.isAnnotationPresent(Prompt.class))
                .map(Method::getName)
                .toList();

        assertThat(prompts.catalogue()).extracting(PromptExpose::nom).containsExactlyInAnyOrderElementsOf(declares);
    }

    @Test
    void chaqueEntreeDuCatalogueEstUtilisableTelleQuelle() {
        for (PromptExpose expose : prompts.catalogue()) {
            assertThat(expose.description())
                    .as("description de %s", expose.nom())
                    .isNotBlank();
            assertThat(expose.texte())
                    .as("texte de %s", expose.nom())
                    .isNotBlank()
                    .doesNotContain("%s")
                    .doesNotContain("null");
        }
    }

    @Test
    void chaquePromptPorteLEditionQuOnLuiDonne() {
        assertThat(prompts.diagnostiquer_contraintes_dures("Canicule 2026")
                        .content()
                        .asText()
                        .text())
                .contains("Canicule 2026");
        assertThat(prompts.verifier_avant_resolution("Canicule 2026")
                        .content()
                        .asText()
                        .text())
                .contains("Canicule 2026");
        assertThat(prompts.resoudre_sans_perdre_le_planning("Canicule 2026")
                        .content()
                        .asText()
                        .text())
                .contains("Canicule 2026");
    }

    /**
     * The edition is optional, and a prompt built without it must still read as
     * a sentence — a hole where the name should be is what an assistant would
     * try to fill by guessing.
     */
    @Test
    void sansEditionLePromptResteUnePhraseComplete() {
        String texte =
                prompts.verifier_avant_resolution(null).content().asText().text();

        assertThat(texte)
                .contains("l'édition par défaut")
                .doesNotContain("«  »")
                .doesNotContain("null");
        assertThat(prompts.diagnostiquer_contraintes_dures("  ")
                        .content()
                        .asText()
                        .text())
                .doesNotContain("«  »")
                .doesNotContain("null");
    }

    @Test
    void lePromptDeDiagnosticRappelleQueLesAnimateursRestentAnonymes() {
        assertThat(prompts.diagnostiquer_contraintes_dures(null)
                        .content()
                        .asText()
                        .text())
                .contains("id")
                .contains("nominative");
    }

    @Test
    void leCatalogueExposeToutesLesContraintesDuSolveur() {
        String markdown = resources.catalogueContraintes().text();

        assertThat(ConstraintCatalog.definitions()).isNotEmpty();
        for (var definition : ConstraintCatalog.definitions()) {
            assertThat(markdown)
                    .as("la contrainte %s doit figurer au catalogue", definition.name())
                    .contains(definition.name())
                    .contains(definition.description());
        }
    }

    /**
     * The URI is declared twice — on the annotation and in the contents — and
     * the two must agree: a client resolves what it read in the listing, so a
     * mismatch makes the resource unreachable while both halves look right.
     */
    @Test
    void chaqueRessourceRenvoieLuriQuElleAnnonce() throws Exception {
        for (Method methode : McpResources.class.getDeclaredMethods()) {
            Resource declaration = methode.getAnnotation(Resource.class);
            if (declaration == null) {
                continue;
            }
            methode.setAccessible(true);
            TextResourceContents contenu = (TextResourceContents) methode.invoke(resources);
            assertThat(contenu.uri())
                    .as("uri renvoyée par %s", methode.getName())
                    .isEqualTo(declaration.uri());
            assertThat(contenu.text()).as("contenu de %s", methode.getName()).isNotBlank();
        }
    }

    @Test
    void leVocabulaireDitQueLesAnimateursNontQuUnIdentifiant() {
        assertThat(resources.vocabulaire().text())
                .contains("qu'un id")
                .contains("Vacation")
                .contains("Journée type");
    }

    /**
     * Declaring a prompt is not announcing it: the extension has to pick the
     * bean up. This asks the server's own registry, so a prompt that compiles
     * but never reaches a client fails here rather than in a conversation.
     */
    @Test
    void lesPromptsEtLesRessourcesSontAnnoncesParLeServeur() {
        for (Method methode : McpPrompts.class.getDeclaredMethods()) {
            if (methode.isAnnotationPresent(Prompt.class)) {
                assertThat(promptManager.getPrompt(methode.getName()))
                        .as("le prompt %s doit être annoncé", methode.getName())
                        .isNotNull();
            }
        }
        for (Method methode : McpResources.class.getDeclaredMethods()) {
            Resource declaration = methode.getAnnotation(Resource.class);
            if (declaration != null) {
                assertThat(declaration.uri()).startsWith("planning://");
                assertThat(resourceManager.getResource(declaration.uri()))
                        .as("la ressource %s doit être annoncée", declaration.uri())
                        .isNotNull();
            }
        }
    }
}
