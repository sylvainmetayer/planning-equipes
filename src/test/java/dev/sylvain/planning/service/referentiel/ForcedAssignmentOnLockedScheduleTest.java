package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.referentiel.ForcedAssignmentOnLockedSchedule.PlaceTenue;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The deadlock a lock and a forced assignment make together. */
class ForcedAssignmentOnLockedScheduleTest {

    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);
    private static final LocalDate DIMANCHE = SAMEDI.plusDays(1);

    private final Creneau samediMatin = new Creneau(1L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(13, 0));
    private final Creneau dimancheMatin = new Creneau(2L, 2, DIMANCHE, LocalTime.of(10, 0), LocalTime.of(13, 0));
    private final Stand plateau = new Stand("PLATEAU", "Plateau", Set.of("JEUX"), 1, 1, false);

    private static final Animateur ALICE = new Animateur("A1", "Prénom", "Nom", LocalDate.of(1990, 1, 1), false);
    private static final Animateur BOB = new Animateur("A2", "Prénom", "Nom", LocalDate.of(1990, 1, 1), false);

    private static ContrainteAdHoc forced(Animateur... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc("C01", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setAnimateursConcernes(List.of(animateurs));
        return contrainte;
    }

    private static VerrouillagePlanning surAnimateur(String animateurId) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
        verrouillage.setAnimateurId(animateurId);
        return verrouillage;
    }

    private static VerrouillagePlanning surAnimateurSurCreneau(String animateurId, long creneauId) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning("V2", TypeVerrouillage.ANIMATEUR_CRENEAU);
        verrouillage.setAnimateurId(animateurId);
        verrouillage.setCreneauId(creneauId);
        return verrouillage;
    }

    private List<ForcedAssignmentOnLockedSchedule.Conflit> detect(
            ContrainteAdHoc contrainte, List<VerrouillagePlanning> verrouillages, Set<PlaceTenue> tenues) {
        return ForcedAssignmentOnLockedSchedule.detectAll(
                List.of(contrainte),
                verrouillages,
                List.of(plateau),
                List.of(samediMatin, dimancheMatin),
                tenues,
                null);
    }

    @Test
    void aFrozenAnimateurForcedOntoASeatTheyDoNotHoldIsADeadlock() {
        assertThat(detect(forced(ALICE), List.of(surAnimateur("A1")), Set.of()))
                .singleElement()
                .satisfies(conflit -> {
                    assertThat(conflit.dates()).containsExactly(SAMEDI, DIMANCHE);
                    assertThat(conflit.message()).contains("C01").doesNotContain("A1");
                });
    }

    /** The exception is already satisfied by a seat the lock pins: nothing is asked of the solver. */
    @Test
    void aFrozenAnimateurAlreadySeatedInTheScopeIsNoDeadlock() {
        assertThat(detect(forced(ALICE), List.of(surAnimateur("A1")), Set.of(new PlaceTenue("A1", "PLATEAU", 1L))))
                .isEmpty();
    }

    @Test
    void aTimeslotLockLeavesTheOtherDayFree() {
        assertThat(detect(forced(ALICE), List.of(surAnimateurSurCreneau("A1", 1L)), Set.of()))
                .isEmpty();
    }

    @Test
    void aTimeslotLockOnTheWholeScopeIsADeadlock() {
        ContrainteAdHoc surSamedi = forced(ALICE);
        surSamedi.setCreneau(new Creneau(1L, 0, null, null, null));

        assertThat(detect(surSamedi, List.of(surAnimateurSurCreneau("A1", 1L)), Set.of()))
                .singleElement()
                .satisfies(conflit -> assertThat(conflit.dates()).containsExactly(SAMEDI));
    }

    @Test
    void oneUnlockedAnimateurAmongThoseNamedIsEnough() {
        assertThat(detect(forced(ALICE, BOB), List.of(surAnimateur("A1")), Set.of()))
                .isEmpty();
    }

    /**
     * A lock on a stand, a timeslot or a day pins the seats that hold somebody
     * and leaves the empty ones fillable: it never blocks the assignment.
     */
    @Test
    void theOtherLockTypesNeverDeadlockAnException() {
        VerrouillagePlanning surStand = new VerrouillagePlanning("V3", TypeVerrouillage.STAND);
        surStand.setStandId("PLATEAU");
        VerrouillagePlanning surJour = new VerrouillagePlanning("V4", TypeVerrouillage.JOUR);
        surJour.setJour(SAMEDI);

        assertThat(detect(forced(ALICE), List.of(surStand, surJour), Set.of())).isEmpty();
    }

    @Test
    void noLockAndOtherKindsOfExceptionAreNeverDeadlocks() {
        ContrainteAdHoc incompatibilite = new ContrainteAdHoc("C02", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        incompatibilite.setAnimateursConcernes(List.of(ALICE));

        assertThat(detect(forced(ALICE), List.of(), Set.of())).isEmpty();
        assertThat(detect(incompatibilite, List.of(surAnimateur("A1")), Set.of()))
                .isEmpty();
    }

    @Test
    void theWriteIsWarnedWithTheSameReading() {
        assertThat(CoherenceAnalyzer.onContrainteAdHoc(
                        forced(ALICE),
                        List.of(ALICE),
                        List.of(plateau),
                        List.of(samediMatin, dimancheMatin),
                        List.of(surAnimateur("A1")),
                        Set.of(),
                        null))
                .singleElement()
                .satisfies(avertissement -> assertThat(avertissement.type())
                        .isEqualTo(TypeAvertissement.AFFECTATION_FORCEE_SIEGE_VERROUILLE));
    }

    /** Same silence on the past as its two siblings — see ADR 0044. */
    @Test
    void aScopeEntirelyInThePastIsNotReproached() {
        assertThat(ForcedAssignmentOnLockedSchedule.detectAll(
                        List.of(forced(ALICE)),
                        List.of(surAnimateur("A1")),
                        List.of(plateau),
                        List.of(samediMatin, dimancheMatin),
                        Set.of(),
                        new PastHorizon(DIMANCHE.plusDays(1), LocalTime.of(8, 0))))
                .isEmpty();
        assertThat(ForcedAssignmentOnLockedSchedule.detectAll(
                        List.of(forced(ALICE)),
                        List.of(surAnimateur("A1")),
                        List.of(plateau),
                        List.of(samediMatin, dimancheMatin),
                        Set.of(),
                        new PastHorizon(DIMANCHE, LocalTime.of(8, 0))))
                .hasSize(1);
    }
}
