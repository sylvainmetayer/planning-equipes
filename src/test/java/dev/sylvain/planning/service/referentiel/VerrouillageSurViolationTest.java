package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.ViolationFormatter.ViolationReference;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Freezing seats that already break a hard rule is accepted — a lock pins, it
 * does not exempt (décision 0003) — and said.
 */
class VerrouillageSurViolationTest {

    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);
    private static final LocalDate DIMANCHE = SAMEDI.plusDays(1);

    private final List<Creneau> grille = List.of(
            new Creneau(1L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(13, 0)),
            new Creneau(2L, 2, DIMANCHE, LocalTime.of(10, 0), LocalTime.of(13, 0)));

    private static ConstraintDiagnostic hardRule(String name, ViolationReference... references) {
        return new ConstraintDiagnostic(name, "-1hard", references.length, List.of(), null, null, List.of(references));
    }

    private static VerrouillagePlanning verrou(TypeVerrouillage type) {
        return new VerrouillagePlanning("V1", type);
    }

    @Test
    void anAnimateurLockOverTheirOwnHardViolationIsSaid() {
        VerrouillagePlanning surAlice = verrou(TypeVerrouillage.ANIMATEUR);
        surAlice.setAnimateurId("A1");

        assertThat(CoherenceAnalyzer.onVerrouillage(
                        surAlice,
                        List.of(hardRule("reposQuotidienMinimal", new ViolationReference("…", "A1", "PLATEAU", 1L))),
                        grille))
                .singleElement()
                .satisfies(avertissement -> {
                    assertThat(avertissement.type()).isEqualTo(TypeAvertissement.VERROUILLAGE_SUR_VIOLATION_DURE);
                    assertThat(avertissement.message())
                            .contains("V1")
                            .contains("reposQuotidienMinimal")
                            .doesNotContain("A1");
                });
    }

    /** A day lock reads the day of the timeslot a violation names, which only the grid can give. */
    @Test
    void aDayLockReadsTheDayOfTheTimeslotAViolationNames() {
        VerrouillagePlanning surSamedi = verrou(TypeVerrouillage.JOUR);
        surSamedi.setJour(SAMEDI);
        List<ConstraintDiagnostic> samedi =
                List.of(hardRule("animateurDisponible", new ViolationReference("…", "A1", "PLATEAU", 1L)));
        List<ConstraintDiagnostic> dimanche =
                List.of(hardRule("animateurDisponible", new ViolationReference("…", "A1", "PLATEAU", 2L)));

        assertThat(CoherenceAnalyzer.onVerrouillage(surSamedi, samedi, grille)).hasSize(1);
        assertThat(CoherenceAnalyzer.onVerrouillage(surSamedi, dimanche, grille))
                .isEmpty();
        // Without the grid there is no day to read: the lock type simply says nothing.
        assertThat(CoherenceAnalyzer.onVerrouillage(surSamedi, samedi, List.of()))
                .isEmpty();
    }

    @Test
    void aLockOverSomebodyElsesViolationSaysNothing() {
        VerrouillagePlanning surBob = verrou(TypeVerrouillage.ANIMATEUR);
        surBob.setAnimateurId("A2");

        assertThat(CoherenceAnalyzer.onVerrouillage(
                        surBob,
                        List.of(hardRule("reposQuotidienMinimal", new ViolationReference("…", "A1", "PLATEAU", 1L))),
                        grille))
                .isEmpty();
    }

    /** Only the hard rules: a comfort rule in default is what the score is for, not what a lock freezes. */
    @Test
    void aSoftRuleInDefaultIsNotWorthAWord() {
        VerrouillagePlanning surStand = verrou(TypeVerrouillage.STAND);
        surStand.setStandId("PLATEAU");

        assertThat(CoherenceAnalyzer.onVerrouillage(
                        surStand,
                        List.of(new ConstraintDiagnostic(
                                "equilibrerCharge",
                                "-40soft",
                                40,
                                List.of(),
                                null,
                                null,
                                List.of(new ViolationReference("…", "A1", "PLATEAU", 1L)))),
                        grille))
                .isEmpty();
    }

    /**
     * No analysis, nothing to read — and nothing guessed. An edition nobody has
     * solved is the state this warning has nothing to say about.
     */
    @Test
    void anEditionWithoutAnAnalysisIsNotGuessedAt() {
        VerrouillagePlanning surAlice = verrou(TypeVerrouillage.ANIMATEUR);
        surAlice.setAnimateurId("A1");

        assertThat(CoherenceAnalyzer.onVerrouillage(surAlice, List.of(), grille))
                .isEmpty();
        assertThat(CoherenceAnalyzer.onVerrouillage(surAlice, null, grille)).isEmpty();
        assertThat(CoherenceAnalyzer.onVerrouillage(null, List.of(hardRule("animateurDisponible")), grille))
                .isEmpty();
    }

    /**
     * A violation naming nobody points at an <b>empty</b> seat, which a lock
     * leaves fillable: {@code ProblemBuilder} pins a covered seat only when it
     * holds somebody. Counting it made « je fige cette journée relue » warn on
     * a day whose only fault is seats still open.
     */
    @Test
    void aViolationNamingNobodyIsNotFrozenByTheLock() {
        VerrouillagePlanning surSamedi = verrou(TypeVerrouillage.JOUR);
        surSamedi.setJour(SAMEDI);

        assertThat(CoherenceAnalyzer.onVerrouillage(
                        surSamedi,
                        List.of(hardRule("posteDoitEtrePourvu", new ViolationReference("…", null, "PLATEAU", 1L))),
                        grille))
                .isEmpty();
    }

    /** An incomplete lock — a body not validated yet — freezes nothing and says nothing. */
    @Test
    void aLockWithoutATargetSaysNothing() {
        assertThat(CoherenceAnalyzer.onVerrouillage(
                        verrou(TypeVerrouillage.ANIMATEUR),
                        List.of(hardRule("animateurDisponible", new ViolationReference("…", "A1", "PLATEAU", 1L))),
                        grille))
                .isEmpty();
    }
}
