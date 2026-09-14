package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.mcp.InstantaneMcpTools.InstantaneDetailView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotDetail;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotMeta;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Reading a snapshot's content is the one tool here that could hand back
 * thousands of seats at once, so its filtering and its cap are what the tests
 * pin down — together with the count that keeps a truncation visible.
 */
class InstantaneMcpToolsTest {

    private static final SnapshotMeta META = new SnapshotMeta(
            7,
            "Avant retouche",
            false,
            "-2hard/0medium/-40soft",
            3,
            Instant.parse("2026-08-15T10:00:00Z"),
            "DEFAUT",
            "Édition 2026",
            null,
            null,
            Instant.parse("2026-08-16T09:00:00Z"),
            true);

    private static InstantaneMcpTools tools(SnapshotDetail detail) {
        InstantaneMcpTools tools = new InstantaneMcpTools();
        tools.snapshotService = new PlanSnapshotService() {
            @Override
            public SnapshotDetail load(long id) {
                return id == META.id() ? detail : null;
            }
        };
        return tools;
    }

    private static AffectationSnapshot affectation(String posteId, String standId, String animateurId) {
        return new AffectationSnapshot(
                posteId, standId, "C1", "2026-07-11", "10:00", "14:00", animateurId, "10:00", "14:00");
    }

    private static SnapshotDetail detail(List<AffectationSnapshot> affectations) {
        return new SnapshotDetail(META, affectations);
    }

    @Test
    void filtreParStandEtParAnimateur() {
        InstantaneMcpTools tools = tools(detail(
                List.of(affectation("P1", "S1", "A1"), affectation("P2", "S2", "A1"), affectation("P3", "S1", "A2"))));

        InstantaneDetailView parStand = tools.consulter_instantane(7, "S1", null, null, null);
        assertThat(parStand.affectations()).extracting(view -> view.posteId()).containsExactly("P1", "P3");

        InstantaneDetailView parAnimateur = tools.consulter_instantane(7, null, "A1", null, null);
        assertThat(parAnimateur.affectations())
                .extracting(view -> view.posteId())
                .containsExactly("P1", "P2");

        InstantaneDetailView croise = tools.consulter_instantane(7, "S1", "A2", null, null);
        assertThat(croise.affectations()).extracting(view -> view.posteId()).containsExactly("P3");
    }

    @Test
    void leTotalCompteLesAffectationsFiltreesPasCellesRenvoyees() {
        InstantaneMcpTools tools = tools(detail(IntStream.range(0, 500)
                .mapToObj(index -> affectation("P" + index, "S1", "A" + index))
                .toList()));

        InstantaneDetailView vue = tools.consulter_instantane(7, null, null, null, null);

        assertThat(vue.affectations()).hasSize(InstantaneMcpTools.LIMITE_AFFECTATIONS_DEFAUT);
        assertThat(vue.affectationsTotal()).isEqualTo(500);
    }

    @Test
    void uneLimiteExpliciteEstRespectee() {
        InstantaneMcpTools tools = tools(detail(
                List.of(affectation("P1", "S1", "A1"), affectation("P2", "S1", "A2"), affectation("P3", "S1", "A3"))));

        assertThat(tools.consulter_instantane(7, null, null, 2, null).affectations())
                .hasSize(2);
    }

    @Test
    void uneLimiteNonPositiveEstRefusee() {
        InstantaneMcpTools tools = tools(detail(List.of(affectation("P1", "S1", "A1"))));

        assertThatThrownBy(() -> tools.consulter_instantane(7, null, null, 0, null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("limite");
    }

    @Test
    void unInstantaneInconnuEstUneErreurPasUneVueVide() {
        InstantaneMcpTools tools = tools(detail(List.of()));

        assertThatThrownBy(() -> tools.consulter_instantane(999, null, null, null, null))
                .isInstanceOf(BusinessError.NotFound.class)
                .hasMessageContaining("999");
    }

    @Test
    void unPosteNonPourvuGardeUnAnimateurNul() {
        InstantaneMcpTools tools = tools(detail(List.of(affectation("P1", "S1", null))));

        InstantaneDetailView vue = tools.consulter_instantane(7, null, null, null, null);

        assertThat(vue.affectations())
                .singleElement()
                .satisfies(affectation -> assertThat(affectation.animateurId()).isNull());
        assertThat(vue.instantane().libelle()).isEqualTo("Avant retouche");
    }
}
