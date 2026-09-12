package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Plan;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The diff between a calendar of day templates and the grid, on the natural
 * key (date, début, fin): what is kept with its id, what is created, what goes.
 */
class JourneesTypesMaterialisationTest {

    private static final LocalDate LUNDI = LocalDate.of(2027, 7, 12);
    private static final LocalDate MARDI = LUNDI.plusDays(1);

    private static final JourneeType JOUR_NORMAL = new JourneeType(
            1L,
            "Jour normal",
            List.of(
                    vacation("09:00", "12:00", false),
                    vacation("12:00", "13:00", true),
                    vacation("13:00", "14:00", true),
                    vacation("14:00", "20:00", false)));

    @Test
    void unCreneauIdentiqueGardeSonIdEtUnCreneauManquantEstCree() {
        Creneau matin = creneau(41L, LUNDI, "09:00", "12:00", false);

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), List.of(matin));

        assertThat(plan.conserves()).containsExactly(matin);
        assertThat(plan.aCreer()).hasSize(3);
        assertThat(plan.aCreer()).extracting(Creneau::getId).containsOnlyNulls();
        assertThat(plan.aCreer())
                .filteredOn(Creneau::isCouverturePause)
                .extracting(creneau -> creneau.getHeureDebut().toString())
                .containsExactly("12:00", "13:00");
        assertThat(plan.aSupprimer()).isEmpty();
        assertThat(plan.datesEnEcart()).containsExactly(LUNDI);
    }

    @Test
    void unDrapeauDeRelaisDifferentEstMisAJourSansChangerDId() {
        Creneau midi = creneau(42L, LUNDI, "12:00", "13:00", false);
        List<Creneau> grille = List.of(
                creneau(41L, LUNDI, "09:00", "12:00", false),
                midi,
                creneau(43L, LUNDI, "13:00", "14:00", true),
                creneau(44L, LUNDI, "14:00", "20:00", false));

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), grille);

        assertThat(plan.misAJour()).singleElement().satisfies(corrige -> {
            assertThat(corrige.getId()).isEqualTo(42L);
            assertThat(corrige.isCouverturePause()).isTrue();
        });
        // The input row is never mutated: the preview reads it, only the write changes the database.
        assertThat(midi.isCouverturePause()).isFalse();
        assertThat(plan.conserves()).hasSize(3);
        assertThat(plan.aCreer()).isEmpty();
    }

    @Test
    void unCreneauQueLaJourneeTypeNeNommePasEstSupprimeSurUneDateGouverneeSeulement() {
        Creneau soiree = creneau(45L, LUNDI, "20:00", "00:00", false);
        Creneau mardiLibre = creneau(46L, MARDI, "10:00", "11:00", false);
        List<Creneau> grille = List.of(
                creneau(41L, LUNDI, "09:00", "12:00", false),
                creneau(42L, LUNDI, "12:00", "13:00", true),
                creneau(43L, LUNDI, "13:00", "14:00", true),
                creneau(44L, LUNDI, "14:00", "20:00", false),
                soiree,
                mardiLibre);

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), grille);

        assertThat(plan.aSupprimer()).containsExactly(soiree);
        assertThat(plan.conserves()).doesNotContain(mardiLibre);
        assertThat(plan.aCreer()).isEmpty();
        assertThat(plan.datesEnEcart()).containsExactly(LUNDI);
    }

    @Test
    void uneGrilleDejaConformeNeChangeRienEtNaAucuneDateEnEcart() {
        List<Creneau> grille = List.of(
                creneau(41L, LUNDI, "09:00", "12:00", false),
                creneau(42L, LUNDI, "12:00", "13:00", true),
                creneau(43L, LUNDI, "13:00", "14:00", true),
                creneau(44L, LUNDI, "14:00", "20:00", false));

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), grille);

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.datesEnEcart()).isEmpty();
    }

    /** The grid's DOUBLON: the first row stands for the key, the repeat goes with the deletions. */
    @Test
    void unDoublonDeLaGrilleEstRameneAUnSeulCreneau() {
        Creneau premier = creneau(41L, LUNDI, "09:00", "12:00", false);
        Creneau doublon = creneau(47L, LUNDI, "09:00", "12:00", false);

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), List.of(premier, doublon));

        assertThat(plan.conserves()).containsExactly(premier);
        assertThat(plan.aSupprimer()).containsExactly(doublon);
    }

    @Test
    void unCalendrierNommantUneJourneeTypeInconnueEstRefuse() {
        assertThatThrownBy(() -> JourneesTypesMaterialisation.planifier(
                        List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 99L)), List.of()))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("99");
    }

    @Test
    void laReconnaissanceRegroupeLesDatesAuxMemesVacationsEtNumeroteDansLOrdreDesDates() {
        LocalDate mercredi = LUNDI.plusDays(2);
        List<Creneau> grille = List.of(
                // Monday: a normal day, typed out of order on purpose.
                creneau(3L, LUNDI, "14:00", "20:00", false),
                creneau(1L, LUNDI, "09:00", "12:00", false),
                creneau(2L, LUNDI, "12:00", "13:00", true),
                // Tuesday: the evening opens too.
                creneau(4L, MARDI, "09:00", "12:00", false),
                creneau(5L, MARDI, "12:00", "13:00", true),
                creneau(6L, MARDI, "14:00", "20:00", false),
                creneau(7L, MARDI, "20:00", "00:00", false),
                // Wednesday: a normal day again.
                creneau(8L, mercredi, "09:00", "12:00", false),
                creneau(9L, mercredi, "12:00", "13:00", true),
                creneau(10L, mercredi, "14:00", "20:00", false));

        Reconnaissance reconnaissance = JourneesTypesMaterialisation.reconnaitre(grille);

        assertThat(reconnaissance.journeesTypes())
                .extracting(JourneeType::getNom)
                .containsExactly("Journée type 1", "Journée type 2");
        assertThat(reconnaissance.journeesTypes().get(0).getVacations())
                .extracting(vacation -> vacation.heureDebut().toString())
                .containsExactly("09:00", "12:00", "14:00");
        assertThat(reconnaissance.journeesTypes().get(1).getVacations()).hasSize(4);
        Long normal = reconnaissance.journeesTypes().get(0).getId();
        Long nocturne = reconnaissance.journeesTypes().get(1).getId();
        assertThat(reconnaissance.calendrier())
                .containsExactly(
                        new Affectation(LUNDI, normal),
                        new Affectation(MARDI, nocturne),
                        new Affectation(mercredi, normal));
        // Applying what was just recognised changes nothing: the round trip is the invariant.
        assertThat(JourneesTypesMaterialisation.planifier(
                                reconnaissance.journeesTypes(), reconnaissance.calendrier(), grille)
                        .isEmpty())
                .isTrue();
    }

    /** The relay flag is part of the day's shape: a 12-13 at full headcount is another kind of day. */
    @Test
    void leDrapeauDeRelaisDistingueDeuxJourneesTypes() {
        Reconnaissance reconnaissance = JourneesTypesMaterialisation.reconnaitre(
                List.of(creneau(1L, LUNDI, "12:00", "13:00", true), creneau(2L, MARDI, "12:00", "13:00", false)));

        assertThat(reconnaissance.journeesTypes()).hasSize(2);
    }

    /**
     * The calendar of a hand-written scenario goes through the same check as the
     * screen's: the same date under two templates reaches the primary key of
     * {@code journee_type_date}, and must be refused as the data error it is
     * rather than as a 500.
     */
    @Test
    void unCalendrierRefuseUneDateAffecteeDeuxFoisOuUneJourneeTypeInconnue() {
        assertThatThrownBy(() -> JourneeTypeService.checkCalendrier(
                        List.of(new Affectation(LUNDI, 1L), new Affectation(LUNDI, 2L)), Set.of(1L, 2L)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining(LUNDI.toString())
                .hasMessageContaining("deux fois");

        assertThatThrownBy(() -> JourneeTypeService.checkCalendrier(List.of(new Affectation(LUNDI, 9L)), Set.of(1L)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("introuvable");

        assertThatThrownBy(() -> JourneeTypeService.checkCalendrier(List.of(new Affectation(null, 1L)), Set.of(1L)))
                .isInstanceOf(BusinessError.Invalid.class);

        // The shape the screen and a well-formed file both send.
        JourneeTypeService.checkCalendrier(
                List.of(new Affectation(LUNDI, 1L), new Affectation(MARDI, 2L)), Set.of(1L, 2L));
    }

    private static VacationType vacation(String debut, String fin, boolean relais) {
        return new VacationType(LocalTime.parse(debut), LocalTime.parse(fin), relais);
    }

    private static Creneau creneau(Long id, LocalDate date, String debut, String fin, boolean relais) {
        Creneau creneau = new Creneau(id, 0, date, LocalTime.parse(debut), LocalTime.parse(fin));
        creneau.setCouverturePause(relais);
        return creneau;
    }
}
