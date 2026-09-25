package dev.sylvain.planning.service.espace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.CarpoolDayView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-day carpool indication of the espace, which says nothing of a day the viewer does not work. */
class EspaceCarpoolDaysTest {

    private static final LocalDate JOUR_UN = LocalDate.of(2026, 7, 10);
    private static final LocalDate JOUR_DEUX = LocalDate.of(2026, 7, 11);

    private final Animateur alice = new Animateur("alice", "alice", "A", LocalDate.of(1990, 1, 1), false);
    private final Animateur bob = new Animateur("bob", "bob", "B", LocalDate.of(1990, 1, 1), false);
    private final Stand stand = new Stand("S", "S", Set.of("JEUX"), 1, 2, false);
    private long sequence;

    @Test
    void aDayOnlyAMateWorksIsNotShownToTheViewer() {
        List<PosteAffectation> postes = List.of(
                poste(alice, JOUR_UN, 9, 17),
                poste(bob, JOUR_UN, 9, 17),
                // Bob alone on the second day: telling Alice « horaires différents
                // ce jour » would tell her Bob works that day.
                poste(bob, JOUR_DEUX, 9, 17));

        List<CarpoolDayView> jours = EspaceAnimateurService.carpoolDays(groupe(), postes, "alice", 30);

        assertThat(jours).containsExactly(new CarpoolDayView(JOUR_UN, true));
        assertThat(EspaceAnimateurService.carpoolDays(groupe(), postes, "bob", 30))
                .containsExactly(new CarpoolDayView(JOUR_UN, true), new CarpoolDayView(JOUR_DEUX, false));
    }

    @Test
    void aViewerOutsideEveryGroupSeesNothing() {
        List<PosteAffectation> postes = List.of(poste(alice, JOUR_UN, 9, 17), poste(bob, JOUR_UN, 9, 17));

        assertThat(EspaceAnimateurService.carpoolDays(groupe(), postes, "carol", 30))
                .isEmpty();
    }

    private List<ContrainteAdHoc> groupe() {
        ContrainteAdHoc groupe = new ContrainteAdHoc("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE);
        groupe.setAnimateursConcernes(new ArrayList<>(List.of(alice, bob)));
        return List.of(groupe);
    }

    private PosteAffectation poste(Animateur animateur, LocalDate date, int debut, int fin) {
        sequence++;
        Creneau creneau =
                new Creneau(sequence, date.equals(JOUR_UN) ? 1 : 2, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
        PosteAffectation poste = new PosteAffectation("P" + sequence, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
