package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What every bundled scenario reads as <b>after being written back out</b>,
 * pinned element by element.
 *
 * <p>The other half of the net of A2 (issue #392). The reader has one; this is
 * the writer's, and it deliberately pins <b>meaning rather than text</b>:
 * rewriting the writer to serialise a {@code ScenarioDto} will legitimately
 * change the shape of the file — key order, quoting — while it must not change
 * a single thing the file <em>says</em>. Asserting on the YAML would fail on
 * the first and miss the second.</p>
 *
 * <p><b>The round trip is not the identity, and that is the point.</b>
 * Measured before writing this: {@code scenario.yml} comes back element for
 * element, while the larger files lose between fourteen and thirty-five of
 * theirs — ids regenerated, emplacements deduplicated, an {@code edition}
 * section {@code ScenarioExport} does not carry. Rather than characterise that
 * loss and risk describing it wrongly, the reference records exactly what the
 * round trip does today. A rewrite that loses one element more says so.</p>
 */
class ScenarioEcritureDifferentielleTest {

    @Test
    void chaqueScenarioEcritPuisReluCorrespondASaReference() throws IOException {
        List<Path> scenarios = ScenariosLivres.all();
        assertThat(scenarios).as("les scénarios livrés doivent être trouvés").hasSizeGreaterThan(5);

        ReferenceComparison comparaison = new ReferenceComparison("scenario-empreintes-aller-retour");
        List<String> ecarts = new ArrayList<>();
        for (Path scenario : scenarios) {
            ecarts.addAll(comparaison.compare(ScenariosLivres.nom(scenario), writeThenReadBack(scenario)));
        }

        if (!comparaison.written().isEmpty()) {
            fail(
                    "Références absentes, elles viennent d'être écrites : %s. Relisez-les, puis commitez-les.",
                    String.join(", ", comparaison.written()));
        }

        assertThat(ecarts).as("""
                        Le scénario réécrit ne se relit plus comme sa référence. Les deux formes \
                        complètes sont dans target/scenario-differentiel/. Si l'écart est voulu, \
                        régénérez la référence — et relisez-la avant de la commiter.""").isEmpty();
    }

    /**
     * The twelve sections {@code PlanningService.exportScenarioYaml} passes,
     * built from a file instead of a database — including the two steps it
     * takes before building them, since both change what gets written: the
     * horaires are resolved onto the créneaux, and the seats are rebuilt from
     * stands × créneaux rather than reused from the read.
     */
    private static ScenarioYamlReader.ScenarioImporte writeThenReadBack(Path scenario) throws IOException {
        String texte = Files.readString(scenario);
        ScenarioYamlReader.ScenarioImporte lu = ScenarioYamlReader.buildFromScenarioText(texte, ParametresLegaux::new);
        ScenarioYamlReader.ReferenceScenario referentiel =
                ScenarioYamlReader.loadReferenceScenario(scenario.getFileName().toString());

        List<Stand> stands = new ArrayList<>(referentiel.standsById().values());
        List<dev.sylvain.planning.domain.Creneau> creneaux =
                new ArrayList<>(referentiel.creneauxParId().values());
        HoraireStandResolver.apply(stands, creneaux);

        String ecrit = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                referentiel.animateurs(),
                stands,
                creneaux,
                // As the production export: no seat list, the import rebuilds it.
                null,
                lu.sections().typologies(),
                emplacements(stands),
                lu.sections().parametresLegaux().orElse(null),
                lu.sections().parametresDecoupage().orElse(null),
                lu.sections().parametresSolveur().orElse(null),
                lu.sections()
                        .contraintes()
                        .map(ScenarioYamlReader.ContraintesScenario::desactivees)
                        .orElse(Set.of()),
                lu.sections()
                        .contraintes()
                        .map(ScenarioYamlReader.ContraintesScenario::poids)
                        .orElse(Map.of()),
                lu.planning().getContraintesAdHoc()));

        return ScenarioYamlReader.buildFromScenarioText(ecrit, ParametresLegaux::new);
    }

    private static List<Emplacement> emplacements(List<Stand> stands) {
        return stands.stream()
                .map(Stand::getEmplacement)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
