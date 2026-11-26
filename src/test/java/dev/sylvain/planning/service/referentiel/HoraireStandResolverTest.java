package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link HoraireStandResolver}: the stored plages are the first layer, and what lies over them is reset. */
class HoraireStandResolverTest {

    private static final LocalDate MERCREDI = LocalDate.of(2026, 7, 8);

    @Test
    void theEffectivePlagesAreTheStoredOnesWhenNothingIsLaidOver() {
        Stand stand = stand();
        stand.getPlages().add(PlageStand.fermeture(null, MERCREDI, LocalTime.of(14, 0), LocalTime.of(16, 0), null));

        HoraireStandResolver.apply(List.of(stand), List.of());

        assertThat(stand.getPlagesEffectives()).isSameAs(stand.getPlages());
    }

    @Test
    void resolvingBringsAStandBackFromAnEarlierConsigne() {
        Stand stand = stand();
        stand.getPlages().add(PlageStand.ouverture(null, MERCREDI, LocalTime.of(10, 0), null, null));
        stand.setPlagesEffectives(new ArrayList<>());

        HoraireStandResolver.apply(List.of(stand), List.of());

        assertThat(stand.getOuverturesEffectives()).hasSize(1);
    }

    @Test
    void aDayCarryingAPlageIsGovernedByIt() {
        Stand stand = stand();
        stand.getPlages().add(PlageStand.ouverture(null, MERCREDI, LocalTime.of(10, 0), null, null));

        assertThat(HoraireStandResolver.sourceOfDay(stand, MERCREDI))
                .isEqualTo(HoraireStandResolver.SourceHoraire.EXCEPTION);
        assertThat(HoraireStandResolver.sourceOfDay(stand, MERCREDI.plusDays(1)))
                .isEqualTo(HoraireStandResolver.SourceHoraire.DEFAUT);
    }

    @Test
    void aDayOnlyTheModelExplainsIsTheNormalRegimeNotAnException() {
        Stand stand = stand();
        PlageStand unfolded = PlageStand.ouverture(null, MERCREDI, LocalTime.of(10, 0), null, null);
        unfolded.setDepliee(true);
        stand.getPlages().add(unfolded);

        assertThat(HoraireStandResolver.sourceOfDay(stand, MERCREDI))
                .isEqualTo(HoraireStandResolver.SourceHoraire.REGLE);

        stand.getPlages().add(PlageStand.ouverture(null, MERCREDI, LocalTime.of(15, 0), null, null));

        assertThat(HoraireStandResolver.sourceOfDay(stand, MERCREDI))
                .isEqualTo(HoraireStandResolver.SourceHoraire.EXCEPTION);
    }

    private static Stand stand() {
        return new Stand("S", "S", Set.of(), 1, 1, false);
    }
}
