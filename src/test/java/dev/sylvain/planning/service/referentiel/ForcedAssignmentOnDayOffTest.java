package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ForcedAssignmentOnDayOffTest {

    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);
    private static final LocalDate DIMANCHE = SAMEDI.plusDays(1);

    private final Creneau samediMatin = new Creneau(1L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(13, 0));
    private final Creneau dimancheMatin = new Creneau(2L, 2, DIMANCHE, LocalTime.of(10, 0), LocalTime.of(13, 0));
    private final Stand plateau = new Stand("PLATEAU", "Plateau", Set.of("JEUX"), 1, 1, false);

    private static Animateur offOn(String id, LocalDate... jours) {
        Animateur animateur = new Animateur(id, "Prénom", "Nom", LocalDate.of(1990, 1, 1), false);
        animateur.setJoursIndisponibles(Set.of(jours));
        return animateur;
    }

    private static ContrainteAdHoc forced(Animateur... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc("C01", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setAnimateursConcernes(List.of(animateurs));
        return contrainte;
    }

    private List<ForcedAssignmentOnDayOff.Conflit> detect(ContrainteAdHoc contrainte, Animateur... animateurs) {
        return ForcedAssignmentOnDayOff.detectAll(
                List.of(contrainte), List.of(animateurs), List.of(plateau), List.of(samediMatin, dimancheMatin));
    }

    @Test
    void aForcedAssignmentOnTheOnlyDayItsAnimateurIsOffIsAConflict() {
        Animateur absent = offOn("A1", SAMEDI);
        ContrainteAdHoc contrainte = forced(absent);
        contrainte.setCreneau(new Creneau(1L, 0, null, null, null));

        assertThat(detect(contrainte, absent)).singleElement().satisfies(conflit -> {
            assertThat(conflit.dates()).containsExactly(SAMEDI);
            assertThat(conflit.message()).contains("C01").contains("2027-09-04").doesNotContain("A1");
        });
    }

    @Test
    void oneAvailableDayOfTheScopeIsEnough() {
        Animateur absentSamedi = offOn("A1", SAMEDI);

        assertThat(detect(forced(absentSamedi), absentSamedi)).isEmpty();
    }

    @Test
    void oneAvailableAnimateurAmongThoseNamedIsEnough() {
        Animateur absent = offOn("A1", SAMEDI, DIMANCHE);
        Animateur present = offOn("A2");

        assertThat(detect(forced(absent, present), absent, present)).isEmpty();
        assertThat(detect(forced(absent), absent)).hasSize(1);
    }

    /** A stand-scoped exception reads the days that stand opens, not every day of the grid. */
    @Test
    void aStandScopeCoversOnlyTheDaysTheStandOpens() {
        Animateur absentSamedi = offOn("A1", SAMEDI);
        plateau.setIndisponibilites(List.of(new IndisponibiliteStand(null, DIMANCHE, LocalTime.of(9, 0), null, null)));
        ContrainteAdHoc contrainte = forced(absentSamedi);
        contrainte.setStand(plateau);

        assertThat(detect(contrainte, absentSamedi))
                .singleElement()
                .satisfies(conflit -> assertThat(conflit.dates()).containsExactly(SAMEDI));
    }

    @Test
    void otherKindsAndUnknownAnimateursAreNeverConflicts() {
        Animateur absent = offOn("A1", SAMEDI, DIMANCHE);
        ContrainteAdHoc indisponibilite = new ContrainteAdHoc("C02", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        indisponibilite.setAnimateursConcernes(List.of(absent));

        assertThat(detect(indisponibilite, absent)).isEmpty();
        assertThat(detect(forced(absent))).isEmpty();
    }

    @Test
    void theWriteIsWarnedWithTheSameReading() {
        Animateur absent = offOn("A1", SAMEDI, DIMANCHE);

        assertThat(CoherenceAnalyzer.onContrainteAdHoc(
                        forced(absent),
                        List.of(absent),
                        List.of(plateau),
                        List.of(samediMatin, dimancheMatin),
                        List.of(),
                        Set.of()))
                .singleElement()
                .satisfies(avertissement -> assertThat(avertissement.type())
                        .isEqualTo(TypeAvertissement.AFFECTATION_FORCEE_JOUR_INDISPONIBLE));
    }
}
