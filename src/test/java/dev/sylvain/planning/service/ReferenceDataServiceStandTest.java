package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;

/**
 * {@link ReferenceDataService#createStand} / {@link ReferenceDataService#updateStand}
 * validation for {@link OuvertureStand} (issue #60 follow-up: the opening
 * mechanic opposite of {@link IndisponibiliteStand}) — every window must be a
 * genuine same-day interval, and a day can never carry both an opening and a
 * closure.
 */
@QuarkusTest
class ReferenceDataServiceStandTest {

    @Inject
    ReferenceDataService referenceDataService;

    private static final LocalDate JOUR = LocalDate.of(2026, 8, 14);

    @Test
    void ouvertureValideEstAcceptee() {
        Stand stand = stand("STAND-OUV-1");
        stand.setOuvertures(
                List.of(new OuvertureStand(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), "Soirée")));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getOuvertures()).hasSize(1);
    }

    @Test
    void ouvertureSansHeureFinEstRejetee() {
        Stand stand = stand("STAND-OUV-2");
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, LocalTime.of(20, 0), null, null)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ouvertureAvecHeureFinAvantHeureDebutEstRejetee() {
        Stand stand = stand("STAND-OUV-3");
        stand.setOuvertures(
                List.of(new OuvertureStand(null, JOUR, LocalTime.of(23, 0), LocalTime.of(20, 0), null)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ouvertureEtFermetureLeMemeJourSontRejetees() {
        Stand stand = stand("STAND-OUV-4");
        stand.setIndisponibilites(
                List.of(new IndisponibiliteStand(null, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0), null)));
        stand.setOuvertures(
                List.of(new OuvertureStand(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), null)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ouvertureEtFermetureDesJoursDifferentsSontAcceptees() {
        Stand stand = stand("STAND-OUV-5");
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), null)));
        stand.setOuvertures(
                List.of(new OuvertureStand(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), null)));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getIndisponibilites()).hasSize(1);
        assertThat(cree.getOuvertures()).hasSize(1);
    }

    private static Stand stand(String id) {
        return new Stand(id, id, Set.of(TypologieJeu.STRATEGIE), 1, 1, false);
    }
}
