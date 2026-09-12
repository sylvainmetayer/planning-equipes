package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class VacationsLigneTest {

    @Test
    void litLesVacationsEtLeMarqueurDeRelais() {
        List<VacationType> vacations =
                VacationsLigne.parse("09:00-12:00, 12:00-13:00 R, 13h-14h r, 14:00-20:00 (R); 20h00-00:00");

        assertThat(vacations)
                .extracting(VacationType::heureDebut, VacationType::heureFin, VacationType::couverturePause)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(9, 0), LocalTime.of(12, 0), false),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(12, 0), LocalTime.of(13, 0), true),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(13, 0), LocalTime.of(14, 0), true),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(14, 0), LocalTime.of(20, 0), true),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(20, 0), LocalTime.MIDNIGHT, false));
    }

    @Test
    void formateCeQuElleLit() {
        String ligne = "09:00-12:00, 12:00-13:00 R, 14:00-20:00";

        assertThat(VacationsLigne.format(VacationsLigne.parse(ligne))).isEqualTo(ligne);
    }

    @Test
    void refuseUneVacationSansFinOuUneHeureIllisible() {
        assertThatThrownBy(() -> VacationsLigne.parse("09:00-"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("09:00-");
        assertThatThrownBy(() -> VacationsLigne.parse("neuf-douze"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("illisible");
        assertThatThrownBy(() -> VacationsLigne.parse("  ")).isInstanceOf(BusinessError.Invalid.class);
    }
}
