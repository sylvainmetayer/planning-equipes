package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.analyse.GroupedArrivalAnalyzer.GroupView;
import dev.sylvain.planning.service.analyse.GroupedArrivalAnalyzer.GroupedArrivalReport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GroupedArrivalAnalyzerTest {

    private static final LocalDate JOUR_UN = LocalDate.of(2026, 7, 10);
    private static final LocalDate JOUR_DEUX = LocalDate.of(2026, 7, 11);

    private final Animateur alice = new Animateur("alice", "alice", "A", LocalDate.of(1990, 1, 1), false);
    private final Animateur bob = new Animateur("bob", "bob", "B", LocalDate.of(1990, 1, 1), false);
    private final Stand stand = new Stand("S", "S", Set.of("JEUX"), 1, 2, false);
    private long sequence;

    @Test
    void readsEachDayAlignedOrNotWithTheSpreadOfBothEnds() {
        GroupView groupe = analyze(
                        poste(alice, JOUR_UN, 9, 0, 17),
                        poste(bob, JOUR_UN, 9, 45, 17),
                        poste(alice, JOUR_DEUX, 9, 0, 17),
                        poste(bob, JOUR_DEUX, 9, 10, 17))
                .groups()
                .getFirst();

        assertThat(groupe.animateurIds()).containsExactly("alice", "bob");
        assertThat(groupe.misalignedDays()).isEqualTo(1);
        assertThat(groupe.days()).hasSize(2);
        assertThat(groupe.days().get(0).aligned()).isFalse();
        assertThat(groupe.days().get(0).arrivalSpreadMinutes()).isEqualTo(45);
        assertThat(groupe.days().get(1).aligned()).isTrue();
    }

    @Test
    void aDayOnlyOneMemberWorksIsMisalignedAndNamesWhoIsMissing() {
        GroupView groupe = analyze(poste(alice, JOUR_UN, 9, 0, 17)).groups().getFirst();

        assertThat(groupe.days()).singleElement().satisfies(jour -> {
            assertThat(jour.aligned()).isFalse();
            assertThat(jour.absent()).containsExactly("bob");
        });
    }

    private GroupedArrivalReport analyze(PosteAffectation... postes) {
        ContrainteAdHoc groupe = new ContrainteAdHoc("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE);
        groupe.setAnimateursConcernes(new ArrayList<>(List.of(alice, bob)));
        return new GroupedArrivalAnalyzer()
                .analyze(
                        new PlanningEvenement(JOUR_UN, List.of(alice, bob), List.of(postes)),
                        List.of(groupe),
                        new ParametresQualite());
    }

    private PosteAffectation poste(Animateur animateur, LocalDate date, int heure, int minute, int fin) {
        sequence++;
        Creneau creneau = new Creneau(
                sequence, date.equals(JOUR_UN) ? 1 : 2, date, LocalTime.of(heure, minute), LocalTime.of(fin, 0));
        PosteAffectation poste = new PosteAffectation("P" + sequence, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
