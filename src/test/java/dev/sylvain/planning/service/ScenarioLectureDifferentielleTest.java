package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.sylvain.planning.domain.ParametresLegaux;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What every bundled scenario reads as, pinned element by element, so that
 * replacing the reader cannot change it by accident.
 *
 * <p>A2 of issue #392 replaces roughly 590 lines of hand-written traversal of
 * {@code Map<String,Object>} by a mapping from {@code ScenarioDto}. That is a
 * lot of business semantics to move — default values, derivations, the order
 * typologies are applied in — and a suite that only checks a handful of fields
 * per scenario would let most of it drift silently. This test is the net, and
 * it is written <b>before</b> the switch rather than after: a reference taken
 * from the new reader would only prove the new reader agrees with itself.</p>
 *
 * <p><b>Read with the default legal parameters, never the database's.</b> The
 * fallback supplier the application passes reads {@code parametres_legaux}, so
 * a scenario that pins none would be canonicalised differently depending on
 * what another test had just written — the first full-suite run of this test
 * failed on exactly that. A reference that depends on the state of a database
 * is not a reference. What is pinned here is what the <em>file</em> says, plus
 * the domain's own defaults.</p>
 */
class ScenarioLectureDifferentielleTest {

    @Test
    void chaqueScenarioLivreSeLitCommeSaReference() throws IOException {
        List<java.nio.file.Path> scenarios = ScenariosLivres.all();
        assertThat(scenarios).as("les scénarios livrés doivent être trouvés").hasSizeGreaterThan(5);

        ReferenceComparison comparaison = new ReferenceComparison("scenario-empreintes");
        List<String> ecarts = new ArrayList<>();
        for (java.nio.file.Path scenario : scenarios) {
            ecarts.addAll(comparaison.compare(ScenariosLivres.nom(scenario),
                    ScenarioYamlReader.buildFromScenarioText(Files.readString(scenario), ParametresLegaux::new)));
        }

        // All of them at once, not the first one: regenerating one reference at
        // a time would take as many passes as there are scenarios.
        if (!comparaison.written().isEmpty()) {
            fail("Références absentes, elles viennent d'être écrites : %s. Relisez-les, puis commitez-les.",
                    String.join(", ", comparaison.written()));
        }

        assertThat(ecarts)
                .as("""
                        Le scénario ne se lit plus comme sa référence. Les deux formes complètes \
                        sont dans target/scenario-differentiel/. Si l'écart est voulu, régénérez \
                        la référence — et relisez-la avant de la commiter.""")
                .isEmpty();
    }
}
