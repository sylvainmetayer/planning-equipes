package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.scenario.ScenariosLivres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The grid every shipped scenario produces, pinned: the créneaux the file
 * reads as, the grid its day templates materialise over them, the grid its
 * stands' hours derive, and the templates the grid implies. A different grid is
 * a different input to the solver, so merging the generators must not move a
 * single créneau. The reference was taken on {@code main} before the merge.
 *
 * <p>One digest per scenario and part, every shipped file included — the full
 * forms of a mismatch are written to {@code target/grilles-generees/}.</p>
 */
class GrilleGenereeNonRegressionTest {

    private static final Path REFERENCE = Path.of("src/test/resources/grilles-generees.txt");

    @Test
    void everyShippedScenarioProducesTheSameGrid() throws Exception {
        Map<String, List<String>> formes = new TreeMap<>();
        for (Path scenario : ScenariosLivres.all()) {
            String nom = ScenariosLivres.nom(scenario);
            ScenarioYamlReader.ScenarioImporte lu =
                    ScenarioYamlReader.buildFromScenarioText(Files.readString(scenario), ParametresLegaux::new);
            ScenarioYamlReader.ReferenceScenario reference = ScenarioYamlReader.loadReferenceScenario(
                    scenario.getFileName().toString());
            List<Creneau> creneaux = List.copyOf(reference.creneauxParId().values());
            formes.put(nom + " lus", lines(creneaux));
            lu.sections()
                    .gabarits()
                    .ifPresent(section -> formes.put(
                            nom + " gabarits",
                            lines(materialised(section.gabarits(), section.calendrier(), creneaux))));
            List<LocalDate> dates =
                    creneaux.stream().map(Creneau::getDate).distinct().sorted().toList();
            if (!dates.isEmpty()) {
                GrilleDepuisFenetres.Derivation derivation = GrilleDepuisFenetres.deriver(
                        List.copyOf(reference.standsById().values()),
                        new GrilleDepuisFenetres.Parametres(
                                dates.get(0),
                                dates.get(dates.size() - 1),
                                LocalTime.MIDNIGHT,
                                GrilleDepuisFenetres.DUREE_MINIMALE_PAR_DEFAUT));
                formes.put(nom + " stands", lines(derivation.creneaux()));
                formes.put(nom + " reconnaissance", recognised(creneaux));
            }
        }

        List<String> attendu = new ArrayList<>();
        Path sortie = Path.of("target/grilles-generees");
        Files.createDirectories(sortie);
        for (Map.Entry<String, List<String>> forme : formes.entrySet()) {
            attendu.add(forme.getKey() + " " + forme.getValue().size() + " " + digest(forme.getValue()));
            Files.write(sortie.resolve(forme.getKey().replace(' ', '.') + ".txt"), forme.getValue());
        }
        if (!Files.exists(REFERENCE)) {
            Files.write(REFERENCE, attendu);
            fail("Référence absente, elle vient d'être écrite : %s. Relisez-la, puis commitez-la.", REFERENCE);
        }
        assertThat(attendu)
                .as(
                        "La grille produite par un scénario livré a changé : c'est une autre entrée pour le solveur. "
                                + "Les formes complètes sont dans %s.",
                        sortie)
                .containsExactlyElementsOf(Files.readAllLines(REFERENCE, StandardCharsets.UTF_8));
    }

    /** The grid after applying the calendar: what it keeps and creates, plus what it leaves alone. */
    private static List<Creneau> materialised(
            List<Gabarit> gabarits, List<JourneesTypesMaterialisation.Affectation> calendrier, List<Creneau> grille) {
        JourneesTypesMaterialisation.Plan plan = JourneesTypesMaterialisation.planifier(gabarits, calendrier, grille);
        List<Creneau> resultante = new ArrayList<>(grille);
        resultante.removeAll(plan.aSupprimer());
        resultante.addAll(plan.aCreer());
        return resultante;
    }

    /** Each date of the grid and the shape of the template it is recognised as, names left out. */
    private static List<String> recognised(List<Creneau> grille) {
        JourneesTypesMaterialisation.Reconnaissance reconnaissance = JourneesTypesMaterialisation.reconnaitre(grille);
        Map<Long, String> signatures =
                reconnaissance.gabarits().stream().collect(Collectors.toMap(Gabarit::getId, Gabarit::signature));
        return reconnaissance.calendrier().stream()
                .map(affectation -> affectation.date() + " " + signatures.get(affectation.gabaritId()))
                .toList();
    }

    private static List<String> lines(List<Creneau> creneaux) {
        return creneaux.stream()
                .sorted(Comparator.comparing(Creneau::getDate)
                        .thenComparing(Creneau::getHeureDebut)
                        .thenComparing(Creneau::getHeureFin))
                .map(c -> c.getDate() + " " + c.getHeureDebut() + "-" + c.getHeureFin())
                .toList();
    }

    private static String digest(List<String> lignes) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(String.join("\n", lignes).getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
    }
}
