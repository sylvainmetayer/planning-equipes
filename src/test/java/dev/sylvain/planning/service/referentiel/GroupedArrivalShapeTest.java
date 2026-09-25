package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A grouped arrival is a car: two to four distinct people, whole days, no timeslot, no stand. */
class GroupedArrivalShapeTest {

    @Test
    void twoToFourDistinctMembersAreAccepted() {
        assertThatCode(() -> ContrainteAdHocService.checkShape(groupe("A1", "A2")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ContrainteAdHocService.checkShape(groupe("A1", "A2", "A3", "A4")))
                .doesNotThrowAnyException();
    }

    @Test
    void oneMemberFiveMembersOrAScopeAreRefused() {
        assertThatThrownBy(() -> ContrainteAdHocService.checkShape(groupe("A1", "A1")))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> ContrainteAdHocService.checkShape(groupe("A1", "A2", "A3", "A4", "A5")))
                .isInstanceOf(BusinessError.Invalid.class);
        ContrainteAdHoc cadree = groupe("A1", "A2");
        cadree.setCreneau(new Creneau(1L, 1, LocalDate.of(2026, 7, 10), LocalTime.of(9, 0), LocalTime.of(12, 0)));
        assertThatThrownBy(() -> ContrainteAdHocService.checkShape(cadree)).isInstanceOf(BusinessError.Invalid.class);
    }

    private static ContrainteAdHoc groupe(String... ids) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc("G", TypeContrainteAdHoc.ARRIVEE_GROUPEE);
        List<Animateur> membres = new ArrayList<>();
        for (String id : ids) {
            Animateur animateur = new Animateur();
            animateur.setId(id);
            membres.add(animateur);
        }
        contrainte.setAnimateursConcernes(membres);
        return contrainte;
    }
}
