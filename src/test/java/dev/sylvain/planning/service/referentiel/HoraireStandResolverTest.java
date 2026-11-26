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

/**
 * {@link HoraireStandResolver}: the stored plages are the reading, and the ones a consigne wrote lie above the
 * stand's own (ADR 0125).
 */
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

    /** The consigne's band carves the stand's own openings; nothing else of the day moves. */
    @Test
    void aConsignesClosureCarvesTheStandsOwnOpenings() {
        Stand stand = stand();
        stand.getPlages().add(PlageStand.ouverture(null, MERCREDI, LocalTime.of(10, 0), LocalTime.of(20, 0), null, 3));
        stand.getPlages()
                .add(consigne(PlageStand.fermeture(null, MERCREDI, LocalTime.of(12, 0), LocalTime.of(18, 0), null)));

        HoraireStandResolver.apply(List.of(stand), List.of());

        assertThat(stand.getIndisponibilitesEffectives()).isEmpty();
        assertThat(stand.getOuverturesEffectives())
                .extracting(p -> p.getHeureDebut() + "-" + p.getHeureFin() + "@" + p.getEffectif())
                .containsExactly("10:00-12:00@3", "18:00-20:00@3");
    }

    /** A stand that closes at 17h every day still reopens on the consigne's window: the consigne lies above. */
    @Test
    void aConsignesOpeningHoldsOverTheStandsOwnClosure() {
        Stand stand = stand();
        stand.getPlages().add(PlageStand.fermeture(null, MERCREDI, LocalTime.of(17, 0), null, "Fermeture du soir"));
        stand.getPlages()
                .add(consigne(PlageStand.fermeture(null, MERCREDI, LocalTime.of(12, 0), LocalTime.of(18, 0), null)));
        stand.getPlages()
                .add(consigne(PlageStand.ouverture(null, MERCREDI, LocalTime.of(18, 0), LocalTime.of(22, 0), null, 2)));

        HoraireStandResolver.apply(List.of(stand), List.of());

        assertThat(stand.getOuverturesEffectives())
                .extracting(p -> p.getHeureDebut() + "-" + p.getHeureFin() + "@" + p.getEffectif())
                .containsExactlyInAnyOrder("00:00-12:00@null", "18:00-22:00@2");
    }

    @Test
    void aDayTheConsigneEmptiesReadsAsClosed() {
        Stand stand = stand();
        stand.getPlages().add(PlageStand.ouverture(null, MERCREDI, LocalTime.of(14, 0), LocalTime.of(16, 0), null));
        stand.getPlages().add(consigne(PlageStand.fermeture(null, MERCREDI, LocalTime.of(12, 0), null, "Arrêté")));

        HoraireStandResolver.apply(List.of(stand), List.of());

        assertThat(stand.getOuverturesEffectives()).isEmpty();
        assertThat(stand.getIndisponibilitesEffectives())
                .extracting(p -> p.getHeureDebut() + "-" + p.getHeureFin() + " " + p.getMotif())
                .containsExactly("00:00-null Arrêté");
    }

    @Test
    void theOwnHoursAndTheSourceOfADayLeaveTheConsigneOut() {
        Stand stand = stand();
        stand.getPlages().add(consigne(PlageStand.fermeture(null, MERCREDI, LocalTime.of(12, 0), null, null)));

        HoraireStandResolver.applyOwnHours(List.of(stand));

        assertThat(stand.getPlagesEffectives()).isEmpty();
        assertThat(HoraireStandResolver.sourceOfDay(stand, MERCREDI))
                .isEqualTo(HoraireStandResolver.SourceHoraire.DEFAUT);
    }

    /** The origin follows the content: a consigne's plage sent back untouched keeps its mark, one edited loses it. */
    @Test
    void aConsignesPlageEditedByHandLosesItsMark() {
        PlageStand stockee =
                consigne(PlageStand.fermeture(1L, MERCREDI, LocalTime.of(12, 0), LocalTime.of(18, 0), "C"));
        PlageStand renvoyee = PlageStand.fermeture(1L, MERCREDI, LocalTime.of(12, 0), LocalTime.of(18, 0), "C");
        PlageStand modifiee = PlageStand.fermeture(2L, MERCREDI, LocalTime.of(12, 0), LocalTime.of(17, 0), "C");

        PlageStand.carryOrigins(List.of(stockee), List.of(renvoyee, modifiee));

        assertThat(renvoyee.isConsigne()).isTrue();
        assertThat(modifiee.isConsigne()).isFalse();
        assertThat(modifiee.isDepliee()).isFalse();
    }

    private static PlageStand consigne(PlageStand plage) {
        plage.setConsigne(true);
        return plage;
    }

    private static Stand stand() {
        return new Stand("S", "S", Set.of(), 1, 1, false);
    }
}
