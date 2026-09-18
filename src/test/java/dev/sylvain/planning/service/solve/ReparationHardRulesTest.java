package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EmptyReferenceData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Seating somebody by hand is scored before it is written (issue #30).
 *
 * <p>The repair screen never offers a candidate breaking a hard rule, so this
 * costs it nothing. {@code affecter_poste} over MCP and
 * {@code POST /api/postes/{id}/affectation} accept any animateur, and that is
 * the door this check closes.</p>
 *
 * <p>Nothing is persisted here: the harness builds {@link PlanningWhatIf} with
 * no persistence at all, so a gesture that gets past the checks fails on the
 * write — which is exactly how the accepted cases are told from the refused
 * ones.</p>
 */
class ReparationHardRulesTest {

    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);

    private final Stand plateau = new Stand("PLATEAU", "Plateau", Set.of("JEUX"), 1, 1, false);
    private final Stand bar = new Stand("BAR", "Bar", Set.of("BAR"), 1, 1, true);
    private final Creneau matin = new Creneau(1L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(13, 0));
    private final Creneau nuit = new Creneau(2L, 1, SAMEDI, LocalTime.of(22, 0), LocalTime.of(23, 30));

    private final Animateur adulte = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
    private final Animateur jeune = new Animateur("A2", "Bob", "Durand", SAMEDI.minusYears(15), false);

    private PlanningEvenement plan(PosteAffectation... postes) {
        PlanningEvenement plan = new PlanningEvenement(
                SAMEDI, new ArrayList<>(List.of(adulte, jeune)), new ArrayList<>(List.of(postes)));
        plan.setParametresLegaux(List.of(new ParametresLegaux()));
        return plan;
    }

    private static PlanningWhatIf whatIf() {
        SolverConfiguration configuration = new SolverConfiguration(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                ConfigProvider.getConfig());
        return new PlanningWhatIf(configuration.diagnosticService(), new EmptyReferenceData(), null, planning -> {});
    }

    /**
     * The case the global hard score alone would let through: filling the empty
     * seat settles one {@code posteDoitEtrePourvu} point and spends it on the
     * night rule, leaving the total flat.
     */
    @Test
    void aMinorSeatedOnANightSlotIsRefusedNamingTheRule() {
        PosteAffectation deNuit = new PosteAffectation("p0", plateau, nuit);

        assertThatThrownBy(() -> whatIf().applyReparations(plan(deNuit), List.of("p0"), "A2"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("nuit");
    }

    @Test
    void aMinorSeatedOnAnAdultsOnlyStandIsRefused() {
        PosteAffectation reserve = new PosteAffectation("p0", bar, matin);

        assertThatThrownBy(() -> whatIf().applyReparations(plan(reserve), List.of("p0"), "A2"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("majeurs");
    }

    /** Two seats at the same hour: the second one breaks the overlap rule, on a seat this write does touch. */
    @Test
    void aPersonSeatedTwiceAtTheSameHourIsRefused() {
        PosteAffectation premier = new PosteAffectation("p0", plateau, matin);
        premier.setAnimateur(adulte);
        PosteAffectation second = new PosteAffectation("p1", bar, matin);

        assertThatThrownBy(() -> whatIf().applyReparations(plan(premier, second), List.of("p1"), "A1"))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    /**
     * Freeing a seat is never refused: it costs its {@code posteDoitEtrePourvu}
     * point whatever happens, and the jour-J absence is the one gesture that
     * must go through on a plan already in trouble. It reaches the write —
     * which this harness has none of.
     */
    @Test
    void emptyingASeatIsNeverScored() {
        PosteAffectation deNuit = new PosteAffectation("p0", plateau, nuit);
        deNuit.setAnimateur(adulte);

        assertThatThrownBy(() -> whatIf().applyReparations(plan(deNuit), List.of("p0"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anAcceptableSeatingGetsThroughToTheWrite() {
        PosteAffectation libre = new PosteAffectation("p0", plateau, matin);

        assertThatThrownBy(() -> whatIf().applyReparations(plan(libre), List.of("p0"), "A1"))
                .isInstanceOf(NullPointerException.class);
        assertThatCode(() -> whatIf().suggererReparations(plan(libre), "p0", null))
                .doesNotThrowAnyException();
    }
}
