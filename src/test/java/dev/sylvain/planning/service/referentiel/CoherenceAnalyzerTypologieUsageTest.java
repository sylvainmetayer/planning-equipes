package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.CoherenceAnalyzer.EtatTypologie;
import dev.sylvain.planning.service.referentiel.CoherenceAnalyzer.TypologieUsage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link CoherenceAnalyzer#typologieUsages}: the case file the Typologies
 * screen replays too ({@code pages/typologies/usage-typologies.spec.ts}). The
 * badge the screen draws and the count the « État de l'édition » reports come
 * from two implementations; this file is what keeps them one definition.
 */
class CoherenceAnalyzerTypologieUsageTest {

    static final Path CAS = Path.of("src/main/webui/src/app/pages/typologies/usage-typologies.cas.json");

    @TestFactory
    Stream<DynamicTest> theSharedCasesYieldTheExpectedUsage() throws IOException {
        JsonNode racine = new ObjectMapper().readTree(CAS.toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode cas : racine.get("cas")) {
            tests.add(DynamicTest.dynamicTest(cas.get("nom").asText(), () -> {
                List<TypologieUsage> usages = CoherenceAnalyzer.typologieUsages(
                        typologies(cas.get("typologies")),
                        stands(cas.get("stands")),
                        animateurs(cas.get("animateurs")));
                JsonNode attendu = cas.get("attendu");
                assertThat(usages).hasSize(attendu.size());
                for (TypologieUsage usage : usages) {
                    JsonNode ligne = attendu.get(usage.typologieId());
                    assertThat(usage)
                            .as(usage.typologieId())
                            .isEqualTo(new TypologieUsage(
                                    usage.typologieId(),
                                    ligne.path("competents").asInt(0),
                                    ligne.path("referents").asInt(0),
                                    ligne.path("autonomes").asInt(0),
                                    ligne.path("debutants").asInt(0),
                                    ligne.path("polyvalents").asInt(0),
                                    ligne.path("souhaits").asInt(0),
                                    ligne.path("stands").asInt(0),
                                    EtatTypologie.valueOf(ligne.get("etat").asText())));
                }
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(10);
        return tests.stream();
    }

    @Test
    void theOrphanCountIsTheNumberOfOrphanBadges() {
        List<TypologieItem> typologies = List.of(typologie("ECHECS"), typologie("CARTES"), typologie("DES"));
        Stand stand = new Stand("S1", "S1", Set.of("ECHECS", "CARTES"), 1, 1, false);
        Animateur animateur = new Animateur("A1", "Camille", "Durand", null, false);
        animateur.setCompetences(new HashMap<>(Map.of("DES", NiveauCompetence.REFERENT)));

        assertThat(CoherenceAnalyzer.countOrphanTypologies(typologies, List.of(stand), List.of(animateur)))
                .isEqualTo(2);
        assertThat(CoherenceAnalyzer.countOrphanTypologies(typologies, List.of(stand), List.of()))
                .isZero();
    }

    private static TypologieItem typologie(String id) {
        return new TypologieItem(id, null, id, false, null, null, null);
    }

    private static List<TypologieItem> typologies(JsonNode noeuds) {
        List<TypologieItem> typologies = new ArrayList<>();
        for (JsonNode noeud : noeuds) {
            typologies.add(new TypologieItem(
                    noeud.get("id").asText(),
                    null,
                    noeud.get("id").asText(),
                    noeud.path("ninja").asBoolean(false),
                    null,
                    null,
                    null));
        }
        return typologies;
    }

    private static List<Stand> stands(JsonNode noeuds) {
        List<Stand> stands = new ArrayList<>();
        for (JsonNode noeud : noeuds) {
            Set<String> typologies = new HashSet<>();
            noeud.get("typologies").forEach(typologie -> typologies.add(typologie.asText()));
            stands.add(new Stand(noeud.get("id").asText(), noeud.get("id").asText(), typologies, 1, 1, false));
        }
        return stands;
    }

    private static List<Animateur> animateurs(JsonNode noeuds) {
        List<Animateur> animateurs = new ArrayList<>();
        for (JsonNode noeud : noeuds) {
            Animateur animateur = new Animateur(noeud.get("id").asText(), "Prénom", "Nom", null, false);
            Map<String, NiveauCompetence> competences = new HashMap<>();
            noeud.get("competences")
                    .properties()
                    .forEach(entree -> competences.put(
                            entree.getKey(),
                            NiveauCompetence.valueOf(entree.getValue().asText())));
            animateur.setCompetences(competences);
            Set<String> souhaits = new HashSet<>();
            noeud.path("souhaits").forEach(souhait -> souhaits.add(souhait.asText()));
            animateur.setSouhaits(souhaits);
            animateurs.add(animateur);
        }
        return animateurs;
    }
}
