package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.mcp.McpPrompts.PromptExpose;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
                .map(FeatureNames::of)
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
    void thePublicationPromptAnnouncesThatItSendsMail() {
        String texte = prompts.publishThePlanning(null).content().asText().text();

        assertThat(texte).contains("Publier envoie des courriels");
        assertThat(texte.indexOf("Publier envoie des courriels"))
                .as("the warning comes before the tool it is about")
                .isLessThan(texte.indexOf("publier_planning"));
    }

    /**
     * Snake-case words a prompt may write that name neither a tool nor a
     * prompt, each with the reason it is there. Empty today: every snake-case
     * word of the texts is a citation. A word added here must keep appearing
     * in some text, which {@link #everyExceptionStillAppearsInAPrompt()}
     * holds.
     */
    private static final Map<String, String> NOT_TOOL_NAMES = Map.of();

    /** A citation's shape: {@code lister_stands}, {@code tenir_le_jour_j}. Upper-case codes do not match. */
    private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    /** Every text a prompt hands out — with and without an edition — and every description. */
    private List<String> renderedTexts() throws Exception {
        List<String> texts = new ArrayList<>();
        for (Method method : McpPrompts.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Prompt.class)) {
                continue;
            }
            texts.add(method.getAnnotation(Prompt.class).description());
            for (String edition : Arrays.asList(null, "Année 2026")) {
                texts.add(((PromptMessage) method.invoke(prompts, edition))
                        .content()
                        .asText()
                        .text());
            }
        }
        return texts;
    }

    private static Set<String> snakeCaseWords(String text) {
        Set<String> words = new TreeSet<>();
        Matcher matcher = SNAKE_CASE.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    /**
     * Every prompt cites tools, and a cited tool that does not exist is the
     * defect {@code McpToolNamesTest} was written for. Repeated here on the
     * <b>rendered</b> text: that test reads the source file, so a name built by
     * string concatenation would escape it. A renamed or removed tool
     * therefore cannot leave a prompt behind it: any snake-case word of a
     * prompt must be a tool the server announces, a prompt (prompts send the
     * assistant to one another), or an argued exception.
     */
    @Test
    void everyToolAPromptCitesExists() throws Exception {
        Set<String> known =
                new TreeSet<>(OutilsMcp.all().stream().map(FeatureNames::of).toList());
        prompts.catalogue().forEach(expose -> known.add(expose.nom()));
        known.addAll(NOT_TOOL_NAMES.keySet());

        List<String> unknown = new ArrayList<>();
        for (String text : renderedTexts()) {
            for (String word : snakeCaseWords(text)) {
                if (!known.contains(word)) {
                    unknown.add(word);
                }
            }
        }
        assertThat(unknown)
                .as("snake-case words of the prompts that name no tool and no prompt — a renamed tool, or a "
                        + "word that belongs in NOT_TOOL_NAMES with its reason")
                .isEmpty();
    }

    /** The scan above is only worth anything if every prompt does chain real tools. */
    @Test
    void everyPromptChainsAtLeastThreeTools() throws Exception {
        Set<String> tools =
                new TreeSet<>(OutilsMcp.all().stream().map(FeatureNames::of).toList());
        for (PromptExpose expose : prompts.catalogue()) {
            Set<String> cited = snakeCaseWords(expose.texte());
            cited.retainAll(tools);
            assertThat(cited).as("tools cited by %s", expose.nom()).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void everyExceptionStillAppearsInAPrompt() throws Exception {
        Set<String> everything = new TreeSet<>();
        for (String text : renderedTexts()) {
            everything.addAll(snakeCaseWords(text));
        }
        assertThat(everything).containsAll(NOT_TOOL_NAMES.keySet());
    }

    /** Written by hand, in the order of a real event — from the new edition to the review once it is over. */
    @Test
    void theCatalogueFollowsTheLifecycleOfAnEvent() {
        assertThat(prompts.catalogue())
                .extracting(PromptExpose::nom)
                .containsExactly(
                        "demarrer_une_nouvelle_edition",
                        "saisir_les_horaires_des_stands",
                        "construire_la_grille_de_creneaux",
                        "ouvrir_collecte_et_foire",
                        "traiter_les_declarations_de_disponibilite",
                        "figer_le_referentiel",
                        "savoir_ou_recruter_ou_former",
                        "regler_les_regles",
                        "verifier_avant_resolution",
                        "resoudre_sans_perdre_le_planning",
                        "diagnostiquer_contraintes_dures",
                        "verifier_les_arrivees_groupees",
                        "relire_et_valider_les_journees",
                        "verrouiller_ce_qui_tient",
                        "preparer_un_plan_de_repli",
                        "auditer_avant_diffusion",
                        "publier_le_planning",
                        "suivre_les_confirmations",
                        "traiter_les_demandes_dechange",
                        "reprendre_apres_un_changement_tardif",
                        "tenir_le_jour_j",
                        "tirer_le_bilan_de_l_evenement");
    }

    /** Prompts whose argument is not simply « the edition to work in », with the reason. */
    private static final Map<String, String> OWN_EDITION_FRAME = Map.of(
            "demarrer_une_nouvelle_edition",
            "its argument is the source of the duplication; the new edition's id carries every later call",
            "tirer_le_bilan_de_l_evenement",
            "the event under review is usually over and its edition no longer active: it asks which one");

    /**
     * Every tool refuses a call without its edition (ADR 0072), so every
     * prompt says to pass it on every call — the one it was given, or the
     * active one read from {@code lister_editions}.
     */
    @Test
    void everyPromptCarriesItsEditionOnEveryCall() throws Exception {
        for (Method method : McpPrompts.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Prompt.class)) {
                continue;
            }
            String without = ((PromptMessage) method.invoke(prompts, (String) null))
                    .content()
                    .asText()
                    .text();
            String with = ((PromptMessage) method.invoke(prompts, "Canicule 2026"))
                    .content()
                    .asText()
                    .text();

            assertThat(without)
                    .as("%s without an edition", FeatureNames.of(method))
                    .contains("appelle d'abord lister_editions")
                    .contains("argument edition à chaque outil")
                    .doesNotContain("Canicule 2026");
            if (!OWN_EDITION_FRAME.containsKey(FeatureNames.of(method))) {
                assertThat(without)
                        .as("%s without an edition", FeatureNames.of(method))
                        .contains("appelle d'abord lister_editions et retiens celle marquée ACTIVE");
            }
            assertThat(with)
                    .as("%s with an edition", FeatureNames.of(method))
                    .contains("Canicule 2026")
                    .contains("argument edition à chaque outil");
        }
    }

    /**
     * Only the active edition publishes and mails (ADR 0072): a prompt that
     * names a tool sending mail makes the assistant check its edition first
     * and stop otherwise, rather than discover the refusal — or work around it
     * by activating an edition. Derived from {@code openWorldHint}, like the
     * consent guard above.
     */
    @Test
    void aPromptThatNamesAnOutgoingToolChecksTheActiveEditionFirst() throws Exception {
        List<String> outgoing = outilsSortants();

        for (PromptExpose expose : prompts.catalogue()) {
            if (outgoing.stream().anyMatch(expose.texte()::contains)) {
                assertThat(expose.texte())
                        .as("prompt %s sends mail: it checks the active edition first", expose.nom())
                        .contains(McpPrompts.ACTIVE_EDITION_CHECK);
                assertThat(expose.texte().indexOf(McpPrompts.ACTIVE_EDITION_CHECK))
                        .as("%s: the check comes before the first step", expose.nom())
                        .isLessThan(expose.texte().indexOf("1. "));
            }
        }
    }

    /**
     * Re-sending a planning re-sends the <b>published</b> one: after a
     * same-day reassignment, that is the old planning. Only a publication
     * carries the new one.
     */
    @Test
    void theDayOfPromptNeverResendsTheOldPlanning() {
        String text = prompts.runTheDay(null).content().asText().text();

        assertThat(text)
                .contains("Seul publier_planning envoie le nouveau planning")
                .contains("N'appelle pas envoyer_planning_animateur")
                .contains("« Aujourd'hui »")
                .contains("deplacer_affectation")
                .doesNotContain("Mode jour J");
    }

    /** A consigne shuts a band for every stand: one stand closing goes through its own closure. */
    @Test
    void aSingleStandClosingIsNotAConsigne() {
        String lateChange =
                prompts.recoverFromLateChange(null).content().asText().text();
        String freeze = prompts.freezeTheReferential(null).content().asText().text();

        assertThat(lateChange).contains("ajouter_fermeture_stand").contains("à tous les stands");
        assertThat(freeze).contains("ajouter_fermeture_stand").doesNotContain("fermer un stand tard");
    }

    /** The new edition's prompt never renames or activates its source, and sends the other prompts to the new one. */
    @Test
    void theNewEditionPromptTellsTheSourceFromTheTarget() {
        String text = prompts.startANewEdition("Année 2026").content().asText().text();

        assertThat(text)
                .contains("argument source de dupliquer_edition")
                .contains("n'est jamais celle que tu renommes ou actives")
                .contains("edition=<id de la nouvelle édition>");
    }

    /** A fallback plan is a dated consigne in the living edition, never a second edition (ADR 0072). */
    @Test
    void theFallbackPlanStaysInTheLivingEdition() {
        String text = prompts.prepareAFallbackPlan(null).content().asText().text();

        assertThat(text)
                .contains("n'appelle ni dupliquer_edition ni activer_edition")
                .contains("appliquer_consigne")
                .contains("lever_consigne");
    }

    /**
     * What ADR 0072 removed must not survive in what an assistant reads: no
     * default edition a call would fall back on, no per-edition arming of the
     * nightly sends, no fallback variant living as a duplicated edition — in
     * the prompts, the tool and argument descriptions, and the vocabulary.
     */
    @Test
    void noTextSpeaksOfADefaultEditionOfArmingOrOfAVariantEdition() throws Exception {
        List<String> texts = new ArrayList<>(renderedTexts());
        for (Method method : McpPrompts.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                PromptArg arg = parameter.getAnnotation(PromptArg.class);
                if (arg != null) {
                    texts.add(arg.description());
                }
            }
        }
        for (Method tool : OutilsMcp.all()) {
            texts.add(tool.getAnnotation(Tool.class).description());
            for (Parameter parameter : tool.getParameters()) {
                ToolArg arg = parameter.getAnnotation(ToolArg.class);
                if (arg != null) {
                    texts.add(arg.description());
                }
            }
        }
        texts.add(new McpResources().vocabulaire().text());

        Pattern stale = Pattern.compile(
                "édition par défaut|édition armée|\\barm(?:er|é|ée|ées|és|ement)\\b|variante de repli|édition variante",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        for (String text : texts) {
            assertThat(stale.matcher(text).find())
                    .as("texte périmé depuis l'édition active : %s", text)
                    .isFalse();
        }
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
