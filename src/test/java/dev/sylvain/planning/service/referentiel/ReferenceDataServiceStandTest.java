package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.BusinessError;

/**
 * {@link ReferenceDataService#createStand} / {@link ReferenceDataService#updateStand}
 * validation for {@link OuvertureStand} (issue #60 follow-up: the opening
 * mechanic opposite of {@link IndisponibiliteStand}) — every window must be a
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
                List.of(new OuvertureStand(null, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0), "Soirée")));

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
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, LocalTime.of(20, 0), null, null)));

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
                .hasMessageContaining("STAND-SANS-TYPO")
                .hasMessageContaining("au moins une typologie");
        assertThat(referenceDataService.listStands()).noneMatch(s -> "STAND-SANS-TYPO".equals(s.getId()));
    }

    @Test
    void ouvertureSansHeureDebutEstRejetee() {
        Stand stand = stand("STAND-OUV-2B");
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, null, LocalTime.of(23, 0), null)));

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

    /* ----------------------- Recurring opening hours ---------------------- */

    @Test
    void horaireQuotidienAvecCoupureMeridienneEstAccepte() {
        Stand stand = stand("STAND-HOR-1");
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getHoraires()).hasSize(1);
        assertThat(cree.getHoraires().get(0).getFenetres()).hasSize(2);
    }

    @Test
    void horaireSansFenetreEstRejete() {
        Stand stand = stand("STAND-HOR-2");
        stand.setHoraires(List.of(new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.TOUS, List.of())));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins une fenêtre");
    }

    @Test
    void horaireAvecFenetreInverseeEstRejete() {
        Stand stand = stand("STAND-HOR-3");
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(18, 0), LocalTime.of(14, 0)))));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fenetreAvecEffectifNulOuNegatifEstRejetee() {
        Stand stand = stand("STAND-HOR-EFF-0");
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), 0))));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins 1");

        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), -3))));
        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins 1");
    }

    @Test
    void effectifDeFenetreAuDelaDeLaCapaciteDuStandEstRejete() {
        // A window cannot make mandatory more seats than the stand is declared
        // able to hold: the two figures would contradict each other on screen.
        Stand stand = stand("STAND-HOR-EFF-MAX");
        stand.setEffectifMin(1);
        stand.setEffectifMax(3);
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), 4))));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dépasse l'effectif maximum du stand (3)");

        // At the capacity exactly, it is accepted.
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), 3))));
        Stand cree = referenceDataService.createStand(stand);
        assertThat(cree.getHoraires().get(0).getFenetres().get(0).getEffectif()).isEqualTo(3);

        Stand avecOuverture = stand("STAND-OUV-EFF-MAX");
        avecOuverture.setEffectifMax(2);
        avecOuverture.setOuvertures(List.of(new OuvertureStand(null, LocalDate.of(2026, 7, 10),
                LocalTime.of(10, 0), LocalTime.of(12, 0), null, 5)));
        assertThatThrownBy(() -> referenceDataService.createStand(avecOuverture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dépasse l'effectif maximum du stand (2)");
    }

    @Test
    void ouvertureAvecEffectifNulEstRejetee() {
        Stand stand = stand("STAND-OUV-EFF-0");
        stand.setOuvertures(List.of(new OuvertureStand(null, LocalDate.of(2026, 7, 10), LocalTime.of(10, 0),
                LocalTime.of(12, 0), null, 0)));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins 1");
    }

    @Test
    void fenetreEtOuvertureAvecEffectifSontConserveesTellesQuelles() {
        Stand stand = stand("STAND-HOR-EFF-2");
        stand.setEffectifMax(4);
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), 2),
                new FenetreHoraire(LocalTime.of(14, 0), null, null))));
        stand.setOuvertures(List.of(new OuvertureStand(null, LocalDate.of(2026, 7, 10), LocalTime.of(10, 0),
                LocalTime.of(12, 0), null, 4)));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getHoraires().get(0).getFenetres())
                .extracting(FenetreHoraire::getEffectif)
                .containsExactly(2, null);
        assertThat(cree.getOuvertures().get(0).getEffectif()).isEqualTo(4);
    }

    @Test
    void horaireJoursSemaineSansJourEstRejete() {
        Stand stand = stand("STAND-HOR-4");
        stand.setHoraires(List.of(new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)))));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JOURS_SEMAINE");
    }

    @Test
    void horairePlageSansBornesEstRejete() {
        Stand stand = stand("STAND-HOR-5");
        stand.setHoraires(List.of(new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.PLAGE,
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)))));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLAGE");
    }

    /**
     * The one genuine ambiguity: two rules of the same scope, whose days
     * intersect, one opening and one closing. There is no non-arbitrary winner,
     * so it is refused rather than silently arbitrated.
     */
    @Test
    void deuxHorairesDeMemePorteeEtDeModesOpposesSontRejetes() {
        Stand stand = stand("STAND-HOR-6");
        stand.setHoraires(List.of(
                HoraireStand.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), null)),
                HoraireStand.everyDay(ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(14, 0), null))));

        assertThatThrownBy(() -> referenceDataService.createStand(stand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("portée");
    }

    /** Different scopes are the intended way to express "except on that day": the narrower one wins. */
    @Test
    void deuxHorairesDePorteesDifferentesEtDeModesOpposesSontAcceptes() {
        Stand stand = stand("STAND-HOR-7");
        HoraireStand ferieFerme = new HoraireStand(null, ModeHoraire.FERMETURE, TypeJoursHoraire.DATES,
                List.of(new FenetreHoraire(LocalTime.of(0, 0), null)));
        ferieFerme.setDates(Set.of(JOUR));
        stand.setHoraires(List.of(
                HoraireStand.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), null)),
                ferieFerme));

        Stand cree = referenceDataService.createStand(stand);

        assertThat(cree.getHoraires()).hasSize(2);
    }

    @Test
    void deuxHorairesDatesDeModesOpposesSurDesDatesDisjointesSontAcceptes() {
        Stand stand = stand("STAND-HOR-8");
        HoraireStand ouvertLe14 = new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.DATES,
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)));
        ouvertLe14.setDates(Set.of(JOUR));
        HoraireStand fermeLe15 = new HoraireStand(null, ModeHoraire.FERMETURE, TypeJoursHoraire.DATES,
                List.of(new FenetreHoraire(LocalTime.of(0, 0), null)));
        fermeLe15.setDates(Set.of(JOUR.plusDays(1)));
        stand.setHoraires(List.of(ouvertLe14, fermeLe15));

        assertThat(referenceDataService.createStand(stand).getHoraires()).hasSize(2);
    }

    /** The rules must survive a round-trip through the database, windows and scope included. */
    @Test
    void lesHorairesSontRelusTelsQuEcrits() {
        Stand stand = stand("STAND-HOR-9");
        HoraireStand weekend = new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                        new FenetreHoraire(LocalTime.of(14, 0), null)));
        weekend.setJoursSemaine(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        weekend.setMotif("Week-end");
        stand.setHoraires(List.of(weekend));
        referenceDataService.createStand(stand);

        Stand relu = referenceDataService.listStands().stream()
                .filter(candidat -> candidat.getId().equals("STAND-HOR-9"))
                .findFirst()
                .orElseThrow();

        assertThat(relu.getHoraires()).hasSize(1);
        HoraireStand horaire = relu.getHoraires().get(0);
        assertThat(horaire.getMode()).isEqualTo(ModeHoraire.OUVERTURE);
        assertThat(horaire.getJours()).isEqualTo(TypeJoursHoraire.JOURS_SEMAINE);
        assertThat(horaire.getJoursSemaine()).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(horaire.getMotif()).isEqualTo("Week-end");
        assertThat(horaire.getFenetres()).containsExactly(
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null));
    }

    /** A `DATES` scope round-trips through the comma-separated column V37 stores it in. */
    @Test
    void lesDatesDUnHoraireSontReluesTellesQuEcrites() {
        Stand stand = stand("STAND-HOR-10");
        HoraireStand surDates = new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.DATES,
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)));
        surDates.setDates(Set.of(JOUR, JOUR.plusDays(5)));
        stand.setHoraires(List.of(surDates));
        referenceDataService.createStand(stand);

        Stand relu = referenceDataService.listStands().stream()
                .filter(candidat -> candidat.getId().equals("STAND-HOR-10"))
                .findFirst()
                .orElseThrow();

        assertThat(relu.getHoraires().get(0).getDates()).containsExactly(JOUR, JOUR.plusDays(5));
    }

    /**
     * {@code listSolvedStands} is what the solver builds from: the rules must
     * come back expanded there, and stay rules in {@code listStands} — the CRUD
     * view the UI edits — with nothing written to the dated lists either way.
     *
     * <p>Needs a créneau of its own, because the expansion is defined over the
     * days the active group actually spans; it is removed again so the shared test
     * database comes out of this exactly as it went in. The assertions name that
     * one day rather than counting the expanded windows: the shared database also
     * carries whatever créneaux the rest of the suite created, so the total is
     * not this test's business.</p>
     */
    @Test
    void listStandsResolusEtendLesReglesSansLesPersister() {
        Stand stand = stand("STAND-HOR-11");
        stand.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null))));
        referenceDataService.createStand(stand);
        Creneau creneau = referenceDataService.createCreneau(
                new Creneau(null, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(20, 0)));
        try {
            Stand brut = find(referenceDataService.listStands(), "STAND-HOR-11");
            Stand resolu = find(referenceDataService.listSolvedStands(), "STAND-HOR-11");

            assertThat(brut.getOuvertures()).isEmpty();
            assertThat(brut.getOuverturesEffectives()).isEmpty();
            assertThat(resolu.getOuvertures()).isEmpty();
            assertThat(resolu.getOuverturesEffectives())
                    .filteredOn(ouverture -> JOUR.equals(ouverture.getDate()))
                    .singleElement()
                    .satisfies(ouverture -> {
                        assertThat(ouverture.getHeureDebut()).isEqualTo(LocalTime.of(14, 0));
                        assertThat(ouverture.getHeureFin()).isNull();
                    });
        } finally {
            referenceDataService.deleteCreneau(creneau.getId());
        }
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
