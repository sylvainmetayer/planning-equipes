package dev.sylvain.planning.service.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What an edit changed, compared field by field. Pure: no container, no database. */
class ChampsModifiesTest {

    @Test
    void onlyTheFieldsThatMovedAreNamed() {
        Animateur avant = animateur("A1", "Alice", "Martin");
        Animateur apres = animateur("A1", "Alice", "Durand");
        apres.setEmail("alice@example.org");

        assertThat(ChampsModifies.surAnimateur(avant, apres)).containsExactly("nom", "email");
    }

    /**
     * The reason the comparison exists: the bulk edit re-sends the whole fiche
     * one row at a time, so a list built from the payload would claim every
     * field changed on a write that moved none.
     */
    @Test
    void aWriteThatChangesNothingNamesNothing() {
        Animateur avant = animateur("A1", "Alice", "Martin");
        Animateur apres = animateur("A1", "Alice", "Martin");

        assertThat(ChampsModifies.surAnimateur(avant, apres)).isEmpty();
    }

    @Test
    void collectionsAreComparedByContentNotByIdentity() {
        Animateur avant = animateur("A1", "Alice", "Martin");
        avant.setSouhaits(Set.of("STRATEGIE"));
        Animateur inchange = animateur("A1", "Alice", "Martin");
        inchange.setSouhaits(Set.of("STRATEGIE"));
        Animateur elargi = animateur("A1", "Alice", "Martin");
        elargi.setSouhaits(Set.of("STRATEGIE", "AMBIANCE"));

        assertThat(ChampsModifies.surAnimateur(avant, inchange)).isEmpty();
        assertThat(ChampsModifies.surAnimateur(avant, elargi)).containsExactly("souhaits");
    }

    /** A creation has no before-image: its own action already says what happened. */
    @Test
    void aCreationNamesNoFieldAtAll() {
        assertThat(ChampsModifies.surAnimateur(null, animateur("A1", "Alice", "Martin")))
                .isEmpty();
        assertThat(ChampsModifies.surStand(null, new Stand("S1", "Stand", Set.of(), 1, 2, false)))
                .isEmpty();
    }

    @Test
    void aStandNamesItsCapacityAndItsLocationById() {
        Stand avant = new Stand("S1", "Jeux de plateau", Set.of("STRATEGIE"), 2, 4, false);
        Stand apres = new Stand("S1", "Jeux de plateau", Set.of("STRATEGIE"), 2, 6, true);

        assertThat(ChampsModifies.surStand(avant, apres)).containsExactly("effectifMax", "reserveMajeurs");
    }

    /**
     * The before-image is re-read from the base, the after-image is the
     * caller's: two instances of every rule and window. A rename of a stand
     * carrying one recurring rule used to be journaled « nom, horaires ».
     */
    @Test
    void aStandsScheduleIsComparedByValueNotByInstance() {
        Stand avant = new Stand("S1", "Village", Set.of("STRATEGIE"), 2, 4, false);
        avant.setHoraires(List.of(regle(null)));
        avant.setIndisponibilites(List.of(fermeture(7L, LocalDate.of(2030, 7, 18), 10, 12)));
        Stand apres = new Stand("S1", "Village (grand)", Set.of("STRATEGIE"), 2, 4, false);
        apres.setHoraires(List.of(regle(3L)));
        apres.setIndisponibilites(List.of(fermeture(7L, LocalDate.of(2030, 7, 18), 10, 12)));

        assertThat(ChampsModifies.surStand(avant, apres)).containsExactly("nom");
    }

    /** And a dated closure moved under the same row id used to be journaled as nothing at all. */
    @Test
    void aDatedWindowMovedUnderTheSameIdIsAChange() {
        Stand avant = new Stand("S1", "Village", Set.of("STRATEGIE"), 2, 4, false);
        avant.setIndisponibilites(List.of(fermeture(7L, LocalDate.of(2030, 7, 18), 10, 12)));
        Stand apres = new Stand("S1", "Village", Set.of("STRATEGIE"), 2, 4, false);
        apres.setIndisponibilites(List.of(fermeture(7L, LocalDate.of(2030, 7, 19), 14, 20)));

        assertThat(ChampsModifies.surStand(avant, apres)).containsExactly("indisponibilites");
    }

    private static HoraireStand regle(Long id) {
        return new HoraireStand(
                id,
                ModeHoraire.OUVERTURE,
                TypeJoursHoraire.TOUS,
                List.of(new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(18, 0), 2)));
    }

    private static IndisponibiliteStand fermeture(Long id, LocalDate date, int debut, int fin) {
        return new IndisponibiliteStand(id, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0), "Pause");
    }

    @Test
    void aTimeslotNamesTheHoursThatMoved() {
        Creneau avant = new Creneau(1L, 1, LocalDate.of(2030, 9, 3), LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau apres = new Creneau(1L, 1, LocalDate.of(2030, 9, 3), LocalTime.of(9, 0), LocalTime.of(13, 0));

        assertThat(ChampsModifies.surCreneau(avant, apres)).containsExactly("heureFin");
    }

    /** Nothing the comparison returns may be a value: only names travel. */
    @Test
    void nothingButFieldNamesEverComesOut() {
        Animateur avant = animateur("A1", "Alice", "Martin");
        Animateur apres = animateur("A1", "Bérénice", "Durand");
        apres.setDateNaissance(LocalDate.of(2010, 5, 4));

        assertThat(ChampsModifies.surAnimateur(avant, apres))
                .containsExactly("prenom", "nom", "dateNaissance")
                .allSatisfy(champ -> assertThat(champ).doesNotContain("Alice", "Bérénice", "Martin", "Durand", "2010"));
    }

    private static Animateur animateur(String id, String prenom, String nom) {
        return new Animateur(id, prenom, nom, LocalDate.of(1990, 1, 1), false);
    }
}
