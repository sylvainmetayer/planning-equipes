package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ReferenceDataService#createStand} / {@link ReferenceDataService#updateStand}
 * validation for {@link PlageStand} (issue #60 follow-up: the opening
 * mechanic opposite of {@link PlageStand}) — every window must be a
 * genuine same-day interval, and a day can never carry both an opening and a
 * closure — and for the recurring {@link HoraireStand} rules layered above them:
 * a self-consistent scope, and no two rules of equal scope disagreeing on the
 * mode.
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
                List.of(PlageStand.ouverture(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), "Soirée")));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getOuvertures()).hasSize(1);
    }

    /**
     * A missing {@code heureFin} used to be rejected; since recurring horaires
     * it is the "until closing time" form — the window runs to the end of
     * whichever créneau it is evaluated against, which is how a stand open "from
     * 20:00 to closing" is stated without inventing an hour (and without the
     * {@code 23:59} stand-in a day closing at midnight would otherwise force).
     */
    @Test
    void ouvertureSansHeureFinSignifieJusquALaFermeture() {
        Stand stand = stand("STAND-OUV-2");
        stand.setOuvertures(List.of(PlageStand.ouverture(null, JOUR, LocalTime.of(20, 0), null, null)));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getOuvertures()).hasSize(1);
        assertThat(cree.getOuvertures().get(0).getHeureFin()).isNull();
    }

    /** Issue #343: refused on write, and the message names the stand. */
    @Test
    void aStandWithoutTypologieIsRefusedByName() {
        Stand stand = new Stand("STAND-SANS-TYPO", "Sans typologie", Set.of(), 1, 1, false);

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("« Sans typologie »")
                .hasMessageContaining("au moins une typologie");
        // Nothing written: an id sent is never kept (ADR 0050), so look for the name.
        assertThat(referenceDataService.listStands()).noneMatch(s -> "Sans typologie".equals(s.getNom()));
    }

    @Test
    void ouvertureSansHeureDebutEstRejetee() {
        Stand stand = stand("STAND-OUV-2B");
        stand.setOuvertures(List.of(PlageStand.ouverture(null, JOUR, null, LocalTime.of(23, 0), null)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ouvertureAvecHeureFinAvantHeureDebutEstRejetee() {
        Stand stand = stand("STAND-OUV-3");
        stand.setOuvertures(List.of(PlageStand.ouverture(null, JOUR, LocalTime.of(23, 0), LocalTime.of(20, 0), null)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ouvertureEtFermetureLeMemeJourSontRejetees() {
        Stand stand = stand("STAND-OUV-4");
        stand.setIndisponibilites(
                List.of(PlageStand.fermeture(null, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0), null)));
        stand.setOuvertures(List.of(PlageStand.ouverture(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), null)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ouvertureEtFermetureDesJoursDifferentsSontAcceptees() {
        Stand stand = stand("STAND-OUV-5");
        stand.setIndisponibilites(
                List.of(PlageStand.fermeture(null, JOUR.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), null)));
        stand.setOuvertures(List.of(PlageStand.ouverture(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), null)));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getIndisponibilites()).hasSize(1);
        assertThat(cree.getOuvertures()).hasSize(1);
    }

    /* ------------------------------ Headcounts ------------------------------ */

    @Test
    void effectifDOuvertureAuDelaDeLaCapaciteDuStandEstRejete() {
        // A window cannot make mandatory more seats than the stand is declared
        // able to hold: the two figures would contradict each other on screen.
        Stand stand = stand("STAND-OUV-EFF-MAX");
        stand.setEffectifMin(1);
        stand.setEffectifMax(3);
        stand.setOuvertures(List.of(PlageStand.ouverture(
                null, LocalDate.of(2026, 7, 10), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 4)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dépasse l'effectif maximum du stand (3)");

        // At the capacity exactly, it is accepted.
        stand.setOuvertures(List.of(PlageStand.ouverture(
                null, LocalDate.of(2026, 7, 10), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 3)));
        Stand cree = referenceDataService.createStand(stand);
        assertThat(cree.getOuvertures().get(0).getEffectif()).isEqualTo(3);
    }

    @Test
    void ouvertureAvecEffectifNulEstRejetee() {
        Stand stand = stand("STAND-OUV-EFF-0");
        stand.setOuvertures(List.of(PlageStand.ouverture(
                null, LocalDate.of(2026, 7, 10), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 0)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins 1");
    }

    /* ------------------------------ Round trips ------------------------------ */

    /** The plages survive a round-trip through the database: mode, window, headcount and motif. */
    @Test
    void plagesAreReadBackAsWritten() {
        Stand stand = stand("STAND-PLG-1");
        stand.setEffectifMax(4);
        stand.getPlages().add(PlageStand.ouverture(null, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0), "Matin", 2));
        stand.getPlages().add(PlageStand.ouverture(null, JOUR, LocalTime.of(14, 0), null, null, null));
        stand.getPlages().add(PlageStand.fermeture(null, JOUR.plusDays(1), LocalTime.of(9, 0), null, "Férié"));
        // The id is generated on creation (ADR 0050).
        String id = referenceDataService.createStand(stand).getId();

        Stand relu = find(referenceDataService.listStands(), id);

        assertThat(relu.getOuvertures())
                .extracting(
                        PlageStand::getDate,
                        PlageStand::getHeureDebut,
                        PlageStand::getHeureFin,
                        PlageStand::getEffectif,
                        PlageStand::getMotif)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0), 2, "Matin"),
                        org.assertj.core.groups.Tuple.tuple(JOUR, LocalTime.of(14, 0), null, null, null));
        assertThat(relu.getIndisponibilites())
                .extracting(PlageStand::getDate, PlageStand::getHeureDebut, PlageStand::getMotif)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(JOUR.plusDays(1), LocalTime.of(9, 0), "Férié"));
    }

    private static Stand find(List<Stand> stands, String id) {
        return stands.stream()
                .filter(stand -> stand.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static Stand stand(String id) {
        return new Stand(id, id, Set.of("STRATEGIE"), 1, 1, false);
    }
}
