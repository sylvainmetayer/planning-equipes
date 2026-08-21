package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sylvain.planning.service.PlanningKpiService.PlanningKpi;
import dev.sylvain.planning.service.SnapshotComparaisonService.DiffContrainte;

/**
 * Violation diff of the A/B comparator (issue #70), on the static core: no
 * database, no Quarkus context.
 *
 * <p>The point being pinned down is the difference between <b>zero</b> and
 * <b>not measured</b>: a snapshot captured before its constraint analysis
 * existed knows nothing about a constraint, and reporting that as "0 violation"
 * would turn a blind spot into a clean bill of health.</p>
 */
class SnapshotComparaisonServiceTest {

    private static PlanningKpi kpiAvecViolations(Map<String, Integer> violations) {
        return PlanningKpiService.calculer(List.of(), "0hard/0medium/0soft", violations, null, null);
    }

    @Test
    void unionDesContraintesDesDeuxCotesDansLOrdreBasePuisVariante() {
        Map<String, Integer> base = new LinkedHashMap<>();
        base.put("posteDoitEtrePourvu", 3);
        base.put("reposQuotidien", 1);
        Map<String, Integer> variante = new LinkedHashMap<>();
        variante.put("posteDoitEtrePourvu", 0);
        variante.put("eviterRoulementStandsPremium", 7);

        List<DiffContrainte> diff = SnapshotComparaisonService.diffViolations(
                kpiAvecViolations(base), kpiAvecViolations(variante));

        assertThat(diff)
                .extracting(DiffContrainte::contrainte, DiffContrainte::base, DiffContrainte::variante)
                .containsExactly(
                        tuple("posteDoitEtrePourvu", 3, 0),
                        tuple("reposQuotidien", 1, null),
                        tuple("eviterRoulementStandsPremium", null, 7));
    }

    @Test
    void uneContrainteNonMesureeDUnCoteResteNulleJamaisZero() {
        List<DiffContrainte> diff = SnapshotComparaisonService.diffViolations(
                kpiAvecViolations(Map.of()),
                kpiAvecViolations(Map.of("reposQuotidien", 2)));

        assertThat(diff).singleElement()
                .extracting(DiffContrainte::contrainte, DiffContrainte::base, DiffContrainte::variante)
                .containsExactly("reposQuotidien", null, 2);
    }

    @Test
    void unKpiSansCarteDeViolationsNeCassePasLeDiff() throws Exception {
        // Exactly how the case arises in production: a KPI payload stored by an
        // older format, read back into today's record — the map is missing, so
        // the field lands null rather than empty.
        PlanningKpi sansCarte = new ObjectMapper().readValue("{\"postesTotal\":0}", PlanningKpi.class);
        assertThat(sansCarte.violationsParContrainte()).isNull();

        assertThat(SnapshotComparaisonService.diffViolations(sansCarte, sansCarte)).isEmpty();
        assertThat(SnapshotComparaisonService.diffViolations(
                sansCarte, kpiAvecViolations(Map.of("posteDoitEtrePourvu", 1))))
                .singleElement()
                .extracting(DiffContrainte::base, DiffContrainte::variante)
                .containsExactly(null, 1);
    }
}
