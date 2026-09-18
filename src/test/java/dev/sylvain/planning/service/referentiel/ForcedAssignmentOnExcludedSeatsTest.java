package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reading of « this forced assignment is unsatisfiable » that names a rule
 * of the catalogue rather than a day somebody declared off.
 */
class ForcedAssignmentOnExcludedSeatsTest {

    /** A Saturday of 2027, no public holiday in sight: the night and the caps are the only levers. */
    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);

    private static final LocalDate DIMANCHE = SAMEDI.plusDays(1);

    /** 22 h-1 h: inside the legal night of every minor, outside every adult's business. */
    private final Creneau nuitSamedi = new Creneau(1L, 1, SAMEDI, LocalTime.of(22, 0), LocalTime.of(23, 59));

    private final Creneau matinDimanche = new Creneau(2L, 2, DIMANCHE, LocalTime.of(10, 0), LocalTime.of(13, 0));

    private final Stand plateau = new Stand("PLATEAU", "Plateau", Set.of("JEUX"), 1, 1, false);
    private final Stand bar = new Stand("BAR", "Bar", Set.of("BAR"), 1, 1, true);

    private static Animateur mineur(String id) {
        // Fifteen on the event: under 16, so the night starts at 20 h.
        return new Animateur(id, "Prénom", "Nom", SAMEDI.minusYears(15), false);
    }

    private static Animateur majeur(String id) {
        return new Animateur(id, "Prénom", "Nom", SAMEDI.minusYears(30), false);
    }

    private static ContrainteAdHoc forced(Animateur... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc("C01", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setAnimateursConcernes(List.of(animateurs));
        return contrainte;
    }

    private List<ForcedAssignmentOnExcludedSeats.Conflit> detect(
            ContrainteAdHoc contrainte, List<Creneau> grille, List<Stand> stands, Animateur... animateurs) {
        return ForcedAssignmentOnExcludedSeats.detectAll(List.of(contrainte), List.of(animateurs), stands, grille);
    }

    @Test
    void aMinorForcedOntoANightSlotIsAConflictNamingTheRule() {
        Animateur jeune = mineur("A1");
        ContrainteAdHoc contrainte = forced(jeune);
        contrainte.setCreneau(new Creneau(1L, 0, null, null, null));

        assertThat(detect(contrainte, List.of(nuitSamedi), List.of(plateau), jeune))
                .singleElement()
                .satisfies(conflit -> {
                    assertThat(conflit.contraintesCassees()).contains("travailDeNuitInterditPourMineur");
                    assertThat(conflit.dates()).containsExactly(SAMEDI);
                    assertThat(conflit.message())
                            .contains("C01")
                            .contains("travailDeNuitInterditPourMineur")
                            .doesNotContain("A1");
                });
    }

    @Test
    void aMinorForcedOntoAnAdultsOnlyStandIsAConflict() {
        Animateur jeune = mineur("A1");
        ContrainteAdHoc contrainte = forced(jeune);
        contrainte.setStand(bar);

        assertThat(detect(contrainte, List.of(matinDimanche), List.of(bar), jeune))
                .singleElement()
                .satisfies(
                        conflit -> assertThat(conflit.contraintesCassees()).containsExactly("standReserveAuxMajeurs"));
    }

    @Test
    void oneSeatOfTheScopeTheyMayHoldIsEnough() {
        Animateur jeune = mineur("A1");

        // The night slot excludes them, the Sunday morning one does not.
        assertThat(detect(forced(jeune), List.of(nuitSamedi, matinDimanche), List.of(plateau), jeune))
                .isEmpty();
    }

    @Test
    void oneAcceptableAnimateurAmongThoseNamedIsEnough() {
        Animateur jeune = mineur("A1");
        Animateur adulte = majeur("A2");
        ContrainteAdHoc contrainte = forced(jeune, adulte);
        contrainte.setCreneau(new Creneau(1L, 0, null, null, null));

        assertThat(detect(contrainte, List.of(nuitSamedi), List.of(plateau), jeune, adulte))
                .isEmpty();
    }

    /**
     * The day-off reading owns that case: reporting both would say the same
     * impossibility twice, once in terms nobody asked for.
     */
    @Test
    void anExceptionTheDayOffCheckAlreadyReportsIsLeftToIt() {
        Animateur jeune = mineur("A1");
        jeune.setJoursIndisponibles(Set.of(SAMEDI));
        ContrainteAdHoc contrainte = forced(jeune);
        contrainte.setCreneau(new Creneau(1L, 0, null, null, null));

        assertThat(ForcedAssignmentOnDayOff.detectAll(
                        List.of(contrainte), List.of(jeune), List.of(plateau), List.of(nuitSamedi)))
                .hasSize(1);
        assertThat(detect(contrainte, List.of(nuitSamedi), List.of(plateau), jeune))
                .isEmpty();
    }

    @Test
    void aScopeWithoutASeatAndAnUnknownAnimateurAreNeverConflicts() {
        Animateur jeune = mineur("A1");
        ContrainteAdHoc surStandInconnu = forced(jeune);
        surStandInconnu.setStand(new Stand("FANTOME", "Fantôme", Set.of(), 1, 1, false));

        assertThat(detect(surStandInconnu, List.of(nuitSamedi), List.of(plateau), jeune))
                .isEmpty();
        assertThat(detect(forced(jeune), List.of(nuitSamedi), List.of(plateau))).isEmpty();
        assertThat(detect(forced(jeune), List.of(), List.of(plateau), jeune)).isEmpty();
    }

    @Test
    void otherKindsOfExceptionAreNeverConflicts() {
        Animateur jeune = mineur("A1");
        ContrainteAdHoc incompatibilite = new ContrainteAdHoc("C02", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        incompatibilite.setAnimateursConcernes(List.of(jeune));

        assertThat(detect(incompatibilite, List.of(nuitSamedi), List.of(plateau), jeune))
                .isEmpty();
    }

    @Test
    void theWriteIsWarnedWithTheSameReading() {
        Animateur jeune = mineur("A1");
        ContrainteAdHoc contrainte = forced(jeune);
        contrainte.setCreneau(new Creneau(1L, 0, null, null, null));

        assertThat(CoherenceAnalyzer.onContrainteAdHoc(
                        contrainte, List.of(jeune), List.of(plateau), List.of(nuitSamedi), List.of(), Set.of()))
                .singleElement()
                .satisfies(avertissement ->
                        assertThat(avertissement.type()).isEqualTo(TypeAvertissement.AFFECTATION_FORCEE_MOTIF_LEGAL));
    }
}
