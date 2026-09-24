package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.Anomaly;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.scenario.ScenariosLivres;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The informational anomalies on the scenarios the application ships: an
 * anomaly that fires on correct data stops being read, so every one of them
 * must be founded — and the list says which.
 *
 * <p>Only the ladder rung on headcounts carried by windows has any, and on
 * purpose: its second stand profile is « deux fenêtres qui se recouvrent de
 * 14 h à 18 h, où le plus haut effectif l'emporte », which is exactly what
 * {@link OuvertureStandsAnalyzer.AnomalyType#FENETRES_CHEVAUCHANTES} reports.</p>
 */
class HoraireAnomaliesShippedScenariosTest {

    @Test
    void onlyTheDeliberateOverlapsOfTheShippedScenariosAreReported() throws IOException {
        Map<String, List<String>> found = new TreeMap<>();
        for (Path scenario : ScenariosLivres.all()) {
            ScenarioYamlReader.ReferenceScenario reference = ScenarioYamlReader.loadReferenceScenario(
                    scenario.getFileName().toString());
            List<Stand> stands = new ArrayList<>(reference.standsById().values());
            List<Creneau> creneaux = new ArrayList<>(reference.creneauxParId().values());
            HoraireStandResolver.apply(stands, creneaux);
            List<String> informational = OuvertureStandsAnalyzer.analyze(stands, creneaux).anomalies().stream()
                    .filter(anomalie -> anomalie.type().isInformational())
                    .map(HoraireAnomaliesShippedScenariosTest::describe)
                    .toList();
            if (!informational.isEmpty()) {
                found.put(ScenariosLivres.nom(scenario), informational);
            }
        }

        assertThat(found)
                .containsOnlyKeys("gamme-18-14j-35stands-132animateurs-effectifs-par-fenetre")
                .allSatisfy((nom, anomalies) -> assertThat(anomalies)
                        .containsExactly(
                                "FENETRES_CHEVAUCHANTES STAND-02",
                                "FENETRES_CHEVAUCHANTES STAND-07",
                                "FENETRES_CHEVAUCHANTES STAND-12",
                                "FENETRES_CHEVAUCHANTES STAND-17",
                                "FENETRES_CHEVAUCHANTES STAND-22",
                                "FENETRES_CHEVAUCHANTES STAND-27",
                                "FENETRES_CHEVAUCHANTES STAND-32"));
    }

    private static String describe(Anomaly anomalie) {
        return anomalie.type() + " " + anomalie.standId();
    }
}
