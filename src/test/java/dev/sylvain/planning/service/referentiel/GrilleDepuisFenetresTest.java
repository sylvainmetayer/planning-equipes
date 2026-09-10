package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.GrilleDepuisFenetres.Derivation;
import dev.sylvain.planning.service.referentiel.GrilleDepuisFenetres.Parametres;
import dev.sylvain.planning.service.BusinessError;

/**
 * {@link GrilleDepuisFenetres}: the grid the stands' hours imply — a cut at
 * every opening or closing hour, a créneau on every stretch somebody is open.
 *
 * <p>2026-07-08 is a Wednesday, as in the reference event.</p>
 */
class GrilleDepuisFenetresTest {

    private static final LocalDate JOUR_1 = LocalDate.of(2026, 7, 8);

    private static Stand stand(String id, FenetreHoraire... fenetres) {
        Stand stand = new Stand(id, id, Set.of(), 1, 1, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        if (fenetres.length > 0) {
            stand.getHoraires().add(HoraireStand.everyDay(ModeHoraire.OUVERTURE, fenetres));
        }
        return stand;
    }

    private static FenetreHoraire fenetre(int debut, Integer fin) {
        return new FenetreHoraire(LocalTime.of(debut, 0), fin == null ? null : LocalTime.of(fin, 0));
    }

    private static Parametres oneDay(LocalTime fermeture) {
        return new Parametres(JOUR_1, JOUR_1, fermeture, 15);
    }

    private static List<String> heures(List<Creneau> creneaux) {
        return creneaux.stream().map(creneau -> creneau.getHeureDebut() + "-" + creneau.getHeureFin()).toList();
    }

    @Test
    void chaqueBorneCoupeEtChaqueTrancheOuverteDevientUnCreneau() {
        List<Stand> stands = List.of(
                stand("A", fenetre(10, 12), fenetre(14, null)),
                stand("B", fenetre(10, 13)),
                stand("C", fenetre(18, null)));

        Derivation derivation = GrilleDepuisFenetres.deriver(stands, oneDay(LocalTime.of(20, 0)));

        // 13-14: nobody — no créneau there.
        assertThat(heures(derivation.creneaux())).containsExactly("10:00-12:00", "12:00-13:00", "14:00-18:00",
                "18:00-20:00");
        assertThat(derivation.joursSansFenetre()).isEmpty();
        assertThat(derivation.coupures()).extracting(coupure -> coupure.heure().toString())
                .containsExactly("10:00", "12:00", "13:00", "14:00", "18:00", "20:00");
        assertThat(derivation.coupures().get(0).standIds()).containsExactly("A", "B");
    }

    /** Midnight as the closing hour: the last créneau ends 00:00, the way the domain writes a nocturne. */
    @Test
    void uneFermetureAMinuitDonneUnDernierCreneauQuiFranchitMinuit() {
        Derivation derivation = GrilleDepuisFenetres.deriver(List.of(stand("A", fenetre(20, null))),
                oneDay(LocalTime.MIDNIGHT));

        assertThat(heures(derivation.creneaux())).containsExactly("20:00-00:00");
        assertThat(derivation.creneaux().get(0).getDureeMinutes()).isEqualTo(240);
    }

    @Test
    void uneTrancheTropCourteEstFondueDansSaVoisine() {
        List<Stand> stands = List.of(stand("A", fenetre(10, 12)), stand("B", fenetre(10, 12), fenetre(12, null)));
        // A second stand opening 12:05: a five-minute stretch 12:00-12:05 is an artefact.
        Stand tard = stand("C");
        tard.getHoraires().add(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(12, 5), null)));
        List<Stand> tous = new ArrayList<>(stands);
        tous.add(tard);

        Derivation derivation = GrilleDepuisFenetres.deriver(tous, oneDay(LocalTime.of(18, 0)));

        // The five minutes 12:00-12:05 join the stretch after them: C's 12:05 is snapped to 12:00.
        assertThat(heures(derivation.creneaux())).containsExactly("10:00-12:00", "12:00-18:00");
    }

    /** A short last stretch has nothing after it: it joins the one before. */
    @Test
    void uneDerniereTrancheTropCourteRejointLaPrecedente() {
        Stand a = stand("A", fenetre(10, 18));
        Stand b = stand("B");
        b.getHoraires().add(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(18, 5))));

        Derivation derivation = GrilleDepuisFenetres.deriver(List.of(a, b), oneDay(LocalTime.of(20, 0)));

        assertThat(heures(derivation.creneaux())).containsExactly("10:00-18:05");
    }

    /**
     * "Open from 22:00 until closing" on an event closing at 02:00: the window
     * read as ending before it started, and vanished without a word.
     */
    @Test
    void uneFermetureApresMinuitProlongeLaFenetreAuLendemain() {
        List<Stand> stands = List.of(stand("A", fenetre(20, null)), stand("B", fenetre(22, null)));

        Derivation derivation = GrilleDepuisFenetres.deriver(stands, oneDay(LocalTime.of(2, 0)));

        assertThat(heures(derivation.creneaux())).containsExactly("20:00-22:00", "22:00-02:00");
    }

    /** A five-minute hole between two stands is not worth a cut of the grid. */
    @Test
    void unTrouTropCourtEstRefermeAuLieuDeCouperEnDeux() {
        Stand a = stand("A", fenetre(10, 12));
        Stand b = stand("B");
        b.getHoraires().add(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(12, 5), LocalTime.of(18, 0))));

        Derivation derivation = GrilleDepuisFenetres.deriver(List.of(a, b), oneDay(LocalTime.of(20, 0)));

        assertThat(heures(derivation.creneaux())).containsExactly("10:00-18:00");
        // And the 12:05 cut, merged away, is no longer given as a reason.
        assertThat(derivation.coupures()).extracting(coupure -> coupure.heure().toString())
                .containsExactly("10:00", "18:00");
    }

    @Test
    void unJourOuAucunStandNeDitRienEstSignaleEtSansCreneau() {
        Stand libre = stand("LIBRE");
        Stand ferme = stand("FERME");
        ferme.getIndisponibilites().add(new IndisponibiliteStand(null, JOUR_1, LocalTime.of(12, 0), LocalTime.of(14, 0), null));

        Derivation derivation = GrilleDepuisFenetres.deriver(List.of(libre, ferme), oneDay(LocalTime.of(20, 0)));

        // A stand open by default, or stating closures only, has no known start: nothing to cut on.
        assertThat(derivation.creneaux()).isEmpty();
        assertThat(derivation.joursSansFenetre()).containsExactly(JOUR_1);
    }

    @Test
    void uneExceptionDateeDeplaceLaCoupureDuSeulJourQuElleNomme() {
        Stand stand = stand("A", fenetre(10, 20));
        stand.getOuvertures().add(new OuvertureStand(null, JOUR_1.plusDays(1), LocalTime.of(14, 0), LocalTime.of(20, 0), null));

        Derivation derivation = GrilleDepuisFenetres.deriver(List.of(stand),
                new Parametres(JOUR_1, JOUR_1.plusDays(1), LocalTime.of(20, 0), 15));

        assertThat(heures(derivation.creneaux())).containsExactly("10:00-20:00", "14:00-20:00");
        assertThat(derivation.creneaux().get(1).getDate()).isEqualTo(JOUR_1.plusDays(1));
    }

    /** Deriving from a grid the derivation itself produced gives the grid back: the operation is stable. */
    @Test
    void deriverEstIdempotentSurSesPropresCreneaux() {
        List<Stand> stands = List.of(stand("A", fenetre(10, 12), fenetre(14, null)), stand("B", fenetre(10, 13)));
        Parametres parametres = new Parametres(JOUR_1, JOUR_1.plusDays(2), LocalTime.of(20, 0), 15);

        Derivation premiere = GrilleDepuisFenetres.deriver(stands, parametres);
        Derivation seconde = GrilleDepuisFenetres.deriver(stands, parametres);

        assertThat(heures(seconde.creneaux())).isEqualTo(heures(premiere.creneaux()));
        // 10-12, 12-13, 14-20 on each of the three days: 13-14 is nobody's.
        assertThat(premiere.creneaux()).hasSize(3 * 3);
    }

    @Test
    void refuseUnePlageInverseeOuSansFermeture() {
        assertThatThrownBy(() -> new Parametres(JOUR_1.plusDays(1), JOUR_1, LocalTime.NOON, 15))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> new Parametres(JOUR_1, JOUR_1, null, 15))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("heureFermeture");
    }
}
