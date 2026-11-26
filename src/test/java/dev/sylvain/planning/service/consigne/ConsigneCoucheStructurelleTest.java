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
 * Every reading of a stand's plages goes through {@code StandService.resolve}
 * (ADR 0125).
 *
 * <p>There is no fourth layer any more: a consigne writes its plages on the
 * stands, and a day that then carries both modes — the band on a stand that
 * states its openings — is read as the openings minus the closures by
 * {@code HoraireStandResolver}. A caller that read the stored plages itself,
 * one mode per day, would honour the openings and staff the band an arrêté
 * closed, in silence. Same class of defect as a forgotten {@code edition_id}
 * predicate, same kind of net: {@code StandService} is the one production
 * caller of the resolver, and the readers of a stand's <b>own</b> hours (the
 * consigne's plages left out) are argued here one by one.</p>
 */
class ConsigneCoucheStructurelleTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    private static final String RESOLUTION = "HoraireStandResolver.apply(";

    private static final String OWN_HOURS = "HoraireStandResolver.applyOwnHours(";

    /** The callers of the full resolution outside the entry point, and why each cannot go through it. */
    private static final Map<String, String> RESOLUTION_AVEC_MOTIF = Map.of(
            "service/referentiel/StandService.java",
            "the entry point itself",
            "service/scenario/ScenarioDomainMapper.java",
            "pure and static, reads a scenario file, which never carries a consigne's plages:"
                    + " its consignes are laid on the stands when the file is imported",
            "service/consigne/ConsignePlages.java",
            "lays a consigne on stands in memory for the previews, then resolves them exactly as"
                    + " StandService.resolve would once the plages are written");

    /** The readers of a stand's own hours, and why each must not see the plages a consigne wrote. */
    private static final Map<String, String> HEURES_PROPRES_AVEC_MOTIF = Map.of(
            "service/referentiel/StandService.java",
            "the entry point itself (resolveOwnHours)",
            "service/consigne/ConsigneService.java",
            "computes the day a consigne departs from — what the band takes, what a window inherits —"
                    + " pure and static, the same reading the preview and the write share",
            "service/solve/PlanningService.java",
            "exports the référentiel to a scenario file, whose stands are described without the consigne's"
                    + " plages: the consigne section carries them");

    @Test
    void everyCallerOfTheResolverIsTheEntryPointOrArgued() throws IOException {
        assertThat(callersOutside(RESOLUTION, RESOLUTION_AVEC_MOTIF))
                .as("callers resolving plages themselves — go through StandService.resolve /"
                        + " ReferenceData.resolveHoraires, or argue the exception here")
                .isEmpty();
        assertThat(callersOutside(OWN_HOURS, HEURES_PROPRES_AVEC_MOTIF))
                .as("callers reading a stand's own hours without saying why the consigne must stay out —"
                        + " go through StandService.resolveOwnHours, or argue the exception here")
                .isEmpty();
    }

    @Test
    void everyArguedCallerStillCallsTheResolver() throws IOException {
        List<String> perimes = new ArrayList<>();
        perimes.addAll(stale(RESOLUTION, RESOLUTION_AVEC_MOTIF));
        perimes.addAll(stale(OWN_HOURS, HEURES_PROPRES_AVEC_MOTIF));
        assertThat(perimes)
                .as("argued exceptions that no longer call the resolver: the line outlived what it described")
                .isEmpty();
    }

    private static List<String> callersOutside(String appel, Map<String, String> argues) throws IOException {
        List<String> oublies = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            for (Path fichier :
                    fichiers.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (fichier.endsWith("HoraireStandResolver.java")) {
                    continue;
                }
                String relatif = SOURCES.relativize(fichier).toString().replace('\\', '/');
                if (Files.readString(fichier).contains(appel) && !argues.containsKey(relatif)) {
                    oublies.add(relatif);
                }
            }
        }
        return oublies;
    }

    private static List<String> stale(String appel, Map<String, String> argues) throws IOException {
        List<String> perimes = new ArrayList<>();
        for (String relatif : argues.keySet()) {
            Path fichier = SOURCES.resolve(relatif);
            if (!Files.exists(fichier) || !Files.readString(fichier).contains(appel)) {
                perimes.add(relatif + " (" + appel + ")");
            }
        }
        return perimes;
    }
}
