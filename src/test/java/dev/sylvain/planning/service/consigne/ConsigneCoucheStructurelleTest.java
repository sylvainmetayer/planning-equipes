package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every production caller of {@code HoraireStandResolver.apply} is either the
 * one entry point that lays the consigne layer on top, or argued here.
 *
 * <p>A consigne (issue #4) is the fourth layer of a stand's schedule, applied
 * by {@code StandService.resolve} after the rules and the dated exceptions. A
 * caller that expands the rules itself and stops there reads a nominal day —
 * and staffs 12h-18h on a date an arrêté closed, in silence, exactly the way
 * a forgotten {@code edition_id} predicate once served another edition's
 * data. Same class of defect, same kind of net: a list of the callers that
 * may legitimately stop at the rules, each with the reason.</p>
 */
class ConsigneCoucheStructurelleTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    /**
     * The callers that resolve the rules <b>without</b> the consignes, and why
     * each is right to. All of them reason about the stand's own declaration
     * — what it says, how to rewrite it, what to derive from it — never about
     * the seats a solve is given.
     */
    private static final Map<String, String> SANS_CONSIGNE_AVEC_MOTIF = Map.of(
            "service/referentiel/StandService.java",
                    "the entry point itself: resolves the rules, then lays the consignes on top",
            "service/referentiel/HoraireCompaction.java",
                    "rewrites dated windows into equivalent rules — a rewriting of the stand's own declaration",
            "service/referentiel/HoraireElagage.java",
                    "removes closures the rules make mute — the stand's own declaration again",
            "service/referentiel/GrilleDepuisFenetres.java",
                    "derives a nominal grid from the stands' hours; a consigne is a departure from that grid",
            "service/referentiel/CoherenceService.java",
                    "warns about what a stand, a créneau or an exception says, against the stand's own hours",
            "service/scenario/ScenarioDomainMapper.java",
                    "reads a scenario file into its nominal problem: the consignes section is handed to the import,"
                            + " never laid on the seats it builds",
            "service/solve/PlanningService.java",
                    "exports the référentiel to a scenario file, whose stands are described nominally",
            "service/consigne/ConsigneService.java",
                    "computes the nominal day a consigne departs from — what the band takes, what it inherits");

    @Test
    void everyCallerOfTheRulesResolverIsTheEntryPointOrArgued() throws IOException {
        List<String> oublies = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            for (Path fichier :
                    fichiers.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (fichier.endsWith("HoraireStandResolver.java")) {
                    continue;
                }
                String source = Files.readString(fichier);
                if (!source.contains("HoraireStandResolver.apply(")) {
                    continue;
                }
                String relatif = SOURCES.relativize(fichier).toString().replace('\\', '/');
                if (!SANS_CONSIGNE_AVEC_MOTIF.containsKey(relatif)) {
                    oublies.add(relatif);
                }
            }
        }
        assertThat(oublies)
                .as("callers expanding the rules without the consigne layer — go through "
                        + "StandService.resolve / ReferenceData.resolveHoraires, or argue the exception here")
                .isEmpty();
    }

    @Test
    void everyArguedCallerStillCallsTheResolver() throws IOException {
        List<String> perimes = new ArrayList<>();
        for (String relatif : SANS_CONSIGNE_AVEC_MOTIF.keySet()) {
            Path fichier = SOURCES.resolve(relatif);
            if (!Files.exists(fichier) || !Files.readString(fichier).contains("HoraireStandResolver.apply(")) {
                perimes.add(relatif);
            }
        }
        assertThat(perimes)
                .as("argued exceptions that no longer call the resolver: the line outlived what it described")
                .isEmpty();
    }
}
