package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The meal-break calculation itself, away from the solver: the same code backs
 * {@code coupureRepasObligatoire} and the Pauses screen, so a divergence
 * between the two screens can only come from a divergence here.
 */
class CoupureRepasTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 13);
    private static final FenetreRepas MIDI =
            new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60, true);

    private static final Stand STAND = new Stand("S1", "S1", java.util.Set.of("STRATEGIE"), 1, 3, false);

    private static long sequence;

    private static PosteAffectation poste(int debutHeure, int debutMinute, int finHeure, int finMinute) {
        Creneau creneau = new Creneau(
                ++sequence, 1, JOUR, LocalTime.of(debutHeure, debutMinute), LocalTime.of(finHeure, finMinute));
        PosteAffectation poste = new PosteAffectation("P" + sequence, STAND, creneau);
        poste.setAnimateur(new Animateur("A84", "A84", "A84", LocalDate.of(2000, 1, 1), false));
        return poste;
    }

    @Test
    void rienNEstDuSansTravailDeChaqueCoteDeLaFenetre() {
        assertThat(CoupureRepas.of(List.of(poste(10, 0, 14, 0)), MIDI).due()).isFalse();
        assertThat(CoupureRepas.of(List.of(poste(12, 0, 20, 0)), MIDI).due()).isFalse();
        assertThat(CoupureRepas.of(List.of(poste(8, 0, 11, 0)), MIDI).due()).isFalse();
    }

    @Test
    void leCasRapporteNeLaisseAucunTrou() {
        CoupureRepas coupure = CoupureRepas.of(
                List.of(poste(10, 0, 12, 0), poste(12, 0, 13, 0), poste(13, 0, 14, 0), poste(14, 0, 20, 0)), MIDI);

        assertThat(coupure.due()).isTrue();
        assertThat(coupure.plusGrandTrouMinutes()).isZero();
        assertThat(coupure.manquante()).isTrue();
        assertThat(coupure.minutesManquantes()).isEqualTo(60);
        assertThat(coupure.debut()).isNull();
    }

    @Test
    void leTrouDeMidiEstLuAuPlusTot() {
        CoupureRepas coupure = CoupureRepas.of(List.of(poste(10, 0, 12, 0), poste(13, 0, 20, 0)), MIDI);

        assertThat(coupure.manquante()).isFalse();
        assertThat(coupure.debut()).isEqualTo(LocalTime.of(12, 0));
        assertThat(coupure.fin()).isEqualTo(LocalTime.of(13, 0));
        assertThat(coupure.retardMinutes()).isZero();
    }

    @Test
    void leTrouDeTreizeHeuresPorteSonRetard() {
        CoupureRepas coupure = CoupureRepas.of(List.of(poste(10, 0, 13, 0), poste(14, 0, 20, 0)), MIDI);

        assertThat(coupure.manquante()).isFalse();
        assertThat(coupure.debut()).isEqualTo(LocalTime.of(13, 0));
        assertThat(coupure.retardMinutes()).isEqualTo(60);
    }

    /** A break need not follow the grid's own cuts: 12:30-13:30 is a break too. */
    @Test
    void unTrouAChevalSurLesDeuxDemiFenetresCompte() {
        CoupureRepas coupure = CoupureRepas.of(List.of(poste(10, 0, 12, 30), poste(13, 30, 20, 0)), MIDI);

        assertThat(coupure.manquante()).isFalse();
        assertThat(coupure.debut()).isEqualTo(LocalTime.of(12, 30));
        assertThat(coupure.retardMinutes()).isEqualTo(30);
    }

    @Test
    void seuleLaPartInterneDUnTrouDebordantCompte() {
        CoupureRepas coupure = CoupureRepas.of(List.of(poste(10, 0, 13, 45), poste(14, 45, 20, 0)), MIDI);

        assertThat(coupure.plusGrandTrouMinutes()).isEqualTo(15);
        assertThat(coupure.minutesManquantes()).isEqualTo(45);
    }

    /** Overlapping seats — two stands sharing an hour — must not read as a hole. */
    @Test
    void desPostesQuiSeChevauchentNeCreentPasDeTrou() {
        CoupureRepas coupure = CoupureRepas.of(List.of(poste(10, 0, 13, 0), poste(12, 0, 20, 0)), MIDI);

        assertThat(coupure.plusGrandTrouMinutes()).isZero();
        assertThat(coupure.minutesManquantes()).isEqualTo(60);
    }

    @Test
    void unePosteFranchissantMinuitNeFaussePasLaFenetre() {
        CoupureRepas coupure = CoupureRepas.of(List.of(poste(10, 0, 12, 0), poste(13, 0, 2, 0)), MIDI);

        assertThat(coupure.due()).isTrue();
        assertThat(coupure.manquante()).isFalse();
        assertThat(coupure.debut()).isEqualTo(LocalTime.of(12, 0));
    }

    // --- FenetreRepas.from ------------------------------------------------

    @Test
    void lesDeuxFenetresParDefautSontExploitables() {
        assertThat(FenetreRepas.from(new ParametresLegaux()))
                .extracting(FenetreRepas::libelle)
                .containsExactly(FenetreRepas.MIDI, FenetreRepas.SOIR);
    }

    /** Nobody could ever satisfy a window shorter than the break it demands; it founds no rule. */
    @Test
    void uneFenetrePlusCourteQueSaCoupureEstEcartee() {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setCoupureRepasMinutes(60);
        parametres.setCoupureRepasMidiDebut(LocalTime.of(12, 0));
        parametres.setCoupureRepasMidiFin(LocalTime.of(12, 30));

        assertThat(FenetreRepas.from(parametres))
                .extracting(FenetreRepas::libelle)
                .containsExactly(FenetreRepas.SOIR);
    }

    @Test
    void uneDureeNulleEteintLesDeuxFenetres() {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setCoupureRepasMinutes(0);

        assertThat(FenetreRepas.from(parametres)).isEmpty();
    }
}
