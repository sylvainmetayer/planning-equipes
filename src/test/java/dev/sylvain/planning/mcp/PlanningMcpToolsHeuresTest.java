package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.PlanningMcpTools.HeuresAnimateurView;
import dev.sylvain.planning.mcp.PlanningMcpTools.HeuresView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningHoursReader;
import dev.sylvain.planning.service.analyse.PlanningHoursService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code heures_travaillees} reads the plan its {@code source} names — the
 * persisted one by default, unlike the screen — and says which, with its date.
 */
class PlanningMcpToolsHeuresTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 11);
    private static final Instant SOLVED_AT = Instant.parse("2026-07-10T08:00:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-09T18:30:00Z");

    private static final Animateur ALICE = new Animateur("A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
    private static final Animateur BRUNO = new Animateur("B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);

    @Test
    void withoutASourceTheToolReadsThePersistedPlanEvenWhenSomethingWasPublished() {
        HeuresView view = tools(true).workedHours("E1", null);

        assertThat(view.source()).isEqualTo("persiste");
        assertThat(view.planDate()).isEqualTo(SOLVED_AT);
        assertThat(total(view, "A")).isEqualTo(3.0);
        assertThat(total(view, "B")).isZero();
    }

    @Test
    void thePublishedSourceReadsThePublicationAndIsDatedByIt() {
        HeuresView view = tools(true).workedHours("E1", "publie");

        assertThat(view.source()).isEqualTo("publie");
        assertThat(view.planDate()).isEqualTo(PUBLISHED_AT);
        assertThat(view.semaines()).containsExactly("2026-W28");
        assertThat(total(view, "A")).isZero();
        assertThat(total(view, "B")).isEqualTo(3.0);
    }

    @Test
    void thePublishedSourceIsABusinessRefusalWhileNothingWasPublished() {
        PlanningMcpTools tools = tools(false);

        assertThatThrownBy(() -> tools.workedHours("E1", "publie"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("Aucun planning n'a encore été publié");
        assertThat(tools.workedHours("E1", "persiste").source()).isEqualTo("persiste");
    }

    @Test
    void anUnknownSourceIsRefusedRatherThanReadAsTheDefault() {
        assertThatThrownBy(() -> tools(true).workedHours("E1", "navigateur"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("navigateur");
    }

    private static double total(HeuresView view, String animateurId) {
        return view.animateurs().stream()
                .filter(ligne -> ligne.animateurId().equals(animateurId))
                .findFirst()
                .map(HeuresAnimateurView::total)
                .orElseThrow();
    }

    /** Alice holds the seat in the persisted plan; Bruno held it in the publication, when there is one. */
    private static PlanningMcpTools tools(boolean published) {
        PlanningPersistenceService persistence = new PlanningPersistenceService(null, null, null, null) {
            @Override
            public PlanningResolution loadResolution() {
                return new PlanningResolution(SOLVED_AT, null);
            }

            @Override
            public PlanningEvenement loadPersistedPlanning() {
                return heldBy(ALICE);
            }
        };
        PlanPublieService publications = new PlanPublieService(null, null, null) {
            @Override
            public Optional<PublishedPlan> publicationInForce() {
                return published ? Optional.of(new PublishedPlan(publication(), heldBy(BRUNO))) : Optional.empty();
            }

            @Override
            public boolean jamaisPublie() {
                return !published;
            }
        };
        PlanningHoursReader reader = new PlanningHoursReader(new PlanningHoursService(), persistence, publications);
        return new PlanningMcpTools(null, null, persistence, null, null, reader, null, null, null);
    }

    private static PlanSnapshotService.SnapshotMeta publication() {
        return new PlanSnapshotService.SnapshotMeta(
                1L,
                "Publication",
                false,
                null,
                1,
                PUBLISHED_AT,
                "E1",
                "Édition",
                null,
                PUBLISHED_AT,
                null,
                false,
                List.of());
    }

    private static PlanningEvenement heldBy(Animateur holder) {
        Stand stand = new Stand("S", "Stand", Set.of(), 1, 1, false);
        PosteAffectation poste =
                new PosteAffectation("P", stand, new Creneau(1L, 1, SATURDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        poste.setAnimateur(holder);
        return new PlanningEvenement(SATURDAY, List.of(ALICE, BRUNO), new ArrayList<>(List.of(poste)));
    }
}
