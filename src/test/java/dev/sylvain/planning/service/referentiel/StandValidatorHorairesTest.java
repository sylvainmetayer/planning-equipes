package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.BusinessError;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the write still refuses among overlapping rules, now that the opening
 * report warns about the rest: two rules of one scope and opposite modes on a
 * shared day — the one combination the resolver could only settle by an
 * arbitrary tie-break.
 */
class StandValidatorHorairesTest {

    private static Stand stand(HoraireStand... horaires) {
        Stand stand = new Stand("S", "S", Set.of("T"), 1, 5, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(List.of(horaires));
        return stand;
    }

    private static HoraireStand everyDay(ModeHoraire mode, int debut, int fin, Integer effectif) {
        return HoraireStand.everyDay(mode, new FenetreHoraire(LocalTime.of(debut, 0), LocalTime.of(fin, 0), effectif));
    }

    @Test
    void twoRulesOfOneScopeWithOppositeModesAreStillRefused() {
        Stand stand =
                stand(everyDay(ModeHoraire.OUVERTURE, 10, 18, null), everyDay(ModeHoraire.FERMETURE, 12, 14, null));

        assertThatThrownBy(() -> StandValidator.checkSchedule(stand)).isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void twoRulesOfOneScopeAndOneModeOverlappingAreWrittenAndOnlyReported() {
        Stand stand = stand(everyDay(ModeHoraire.OUVERTURE, 14, 20, 4), everyDay(ModeHoraire.OUVERTURE, 14, 20, 2));

        assertThatCode(() -> StandValidator.checkSchedule(stand)).doesNotThrowAnyException();
    }

    @Test
    void aMoreSpecificClosureOverAnEveryDayOpeningIsWritten() {
        HoraireStand weekEnd = new HoraireStand(
                null,
                ModeHoraire.FERMETURE,
                TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(19, 0))));
        weekEnd.setJoursSemaine(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));

        assertThatCode(() ->
                        StandValidator.checkSchedule(stand(everyDay(ModeHoraire.OUVERTURE, 10, 19, null), weekEnd)))
                .doesNotThrowAnyException();
    }
}
