package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Gabarit;
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

    private static final Gabarit JOUR_NORMAL = new Gabarit(
            1L,
            "Jour normal",
            List.of(
                    vacation("09:00", "12:00"),
                    vacation("12:00", "13:00"),
                    vacation("13:00", "14:00"),
                    vacation("14:00", "20:00")));

    @Test
    void unCreneauIdentiqueGardeSonIdEtUnCreneauManquantEstCree() {
        Creneau matin = creneau(41L, LUNDI, "09:00", "12:00");

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), List.of(matin));

        assertThat(plan.conserves()).containsExactly(matin);
        assertThat(plan.aCreer()).hasSize(3);
        assertThat(plan.aCreer()).extracting(Creneau::getId).containsOnlyNulls();
        assertThat(plan.aCreer())
                .extracting(creneau -> creneau.getHeureDebut().toString())
                .containsExactly("12:00", "13:00", "14:00");
        assertThat(plan.aSupprimer()).isEmpty();
        assertThat(plan.datesEnEcart()).containsExactly(LUNDI);
    }

    @Test
    void unCreneauQueLaJourneeTypeNeNommePasEstSupprimeSurUneDateGouverneeSeulement() {
        Creneau soiree = creneau(45L, LUNDI, "20:00", "00:00");
        Creneau mardiLibre = creneau(46L, MARDI, "10:00", "11:00");
        List<Creneau> grille = List.of(
                creneau(41L, LUNDI, "09:00", "12:00"),
                creneau(42L, LUNDI, "12:00", "13:00"),
                creneau(43L, LUNDI, "13:00", "14:00"),
                creneau(44L, LUNDI, "14:00", "20:00"),
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
                creneau(41L, LUNDI, "09:00", "12:00"),
                creneau(42L, LUNDI, "12:00", "13:00"),
                creneau(43L, LUNDI, "13:00", "14:00"),
                creneau(44L, LUNDI, "14:00", "20:00"));

        Plan plan = JourneesTypesMaterialisation.planifier(
                List.of(JOUR_NORMAL), List.of(new Affectation(LUNDI, 1L)), grille);

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.datesEnEcart()).isEmpty();
    }

    /** The grid's DOUBLON: the first row stands for the key, the repeat goes with the deletions. */
    @Test
    void unDoublonDeLaGrilleEstRameneAUnSeulCreneau() {
        Creneau premier = creneau(41L, LUNDI, "09:00", "12:00");
        Creneau doublon = creneau(47L, LUNDI, "09:00", "12:00");

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
                creneau(3L, LUNDI, "14:00", "20:00"),
                creneau(1L, LUNDI, "09:00", "12:00"),
                creneau(2L, LUNDI, "12:00", "13:00"),
                // Tuesday: the evening opens too.
                creneau(4L, MARDI, "09:00", "12:00"),
                creneau(5L, MARDI, "12:00", "13:00"),
                creneau(6L, MARDI, "14:00", "20:00"),
                creneau(7L, MARDI, "20:00", "00:00"),
                // Wednesday: a normal day again.
                creneau(8L, mercredi, "09:00", "12:00"),
                creneau(9L, mercredi, "12:00", "13:00"),
                creneau(10L, mercredi, "14:00", "20:00"));

        Reconnaissance reconnaissance = JourneesTypesMaterialisation.reconnaitre(grille);

        assertThat(reconnaissance.gabarits())
                .extracting(Gabarit::getNom)
                .containsExactly("Journée type 1", "Journée type 2");
        assertThat(reconnaissance.gabarits().get(0).getVacations())
                .extracting(vacation -> vacation.heureDebut().toString())
                .containsExactly("09:00", "12:00", "14:00");
        assertThat(reconnaissance.gabarits().get(1).getVacations()).hasSize(4);
        Long normal = reconnaissance.gabarits().get(0).getId();
        Long nocturne = reconnaissance.gabarits().get(1).getId();
        assertThat(reconnaissance.calendrier())
                .containsExactly(
                        new Affectation(LUNDI, normal),
                        new Affectation(MARDI, nocturne),
                        new Affectation(mercredi, normal));
        // Applying what was just recognised changes nothing: the round trip is the invariant.
        assertThat(JourneesTypesMaterialisation.planifier(
                                reconnaissance.gabarits(), reconnaissance.calendrier(), grille)
                        .isEmpty())
                .isTrue();
    }

    /** Two days with the same hours are one kind of day; other hours are another. */
    @Test
    void desHeuresDifferentesDistinguentDeuxJourneesTypes() {
        Reconnaissance memes = JourneesTypesMaterialisation.reconnaitre(
                List.of(creneau(1L, LUNDI, "12:00", "13:00"), creneau(2L, MARDI, "12:00", "13:00")));
        Reconnaissance autres = JourneesTypesMaterialisation.reconnaitre(
                List.of(creneau(1L, LUNDI, "12:00", "13:00"), creneau(2L, MARDI, "13:00", "14:00")));

        assertThat(memes.gabarits()).hasSize(1);
        assertThat(autres.gabarits()).hasSize(2);
    }

    /**
     * The calendar of a hand-written scenario goes through the same check as the
     * screen's: the same date under two templates reaches the primary key of
     * {@code journee_type_date}, and must be refused as the data error it is
     * rather than as a 500.
     */
    @Test
    void unCalendrierRefuseUneDateAffecteeDeuxFoisOuUneJourneeTypeInconnue() {
        assertThatThrownBy(() -> GenerateurCreneaux.checkCalendrier(
                        List.of(new Affectation(LUNDI, 1L), new Affectation(LUNDI, 2L)), Set.of(1L, 2L)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining(LUNDI.toString())
                .hasMessageContaining("deux fois");

        assertThatThrownBy(() -> GenerateurCreneaux.checkCalendrier(List.of(new Affectation(LUNDI, 9L)), Set.of(1L)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("introuvable");

        assertThatThrownBy(() -> GenerateurCreneaux.checkCalendrier(List.of(new Affectation(null, 1L)), Set.of(1L)))
                .isInstanceOf(BusinessError.Invalid.class);

        // The shape the screen and a well-formed file both send.
        GenerateurCreneaux.checkCalendrier(
                List.of(new Affectation(LUNDI, 1L), new Affectation(MARDI, 2L)), Set.of(1L, 2L));
    }

    private static VacationType vacation(String debut, String fin) {
        return new VacationType(LocalTime.parse(debut), LocalTime.parse(fin));
    }

    private static Creneau creneau(Long id, LocalDate date, String debut, String fin) {
        Creneau creneau = new Creneau(id, 0, date, LocalTime.parse(debut), LocalTime.parse(fin));
        return creneau;
    }
}
