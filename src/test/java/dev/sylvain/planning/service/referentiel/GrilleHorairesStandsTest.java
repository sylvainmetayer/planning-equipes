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
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.referentiel.GrilleHorairesStands.LigneGrille;
import dev.sylvain.planning.service.referentiel.GrilleHorairesStands.SaisieCellule;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.CelluleCreneau;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.BusinessError;

/**
 * {@link GrilleHorairesStands}: one integer per créneau in, rules and
 * exceptions out — and the grid read back from those is the grid typed.
 *
 * <p>2026-07-08 is a Wednesday, as in the reference event.</p>
 */
class GrilleHorairesStandsTest {

    private static final LocalDate JOUR_1 = LocalDate.of(2026, 7, 8);

    /**
     * The reference grid: 10-12, 12-13, 13-14, 14-20 and a midnight-crossing
     * 20-00 on every day, ids running from 1 in that order day after day.
     */
    private static List<Creneau> grille(int jours) {
        List<Creneau> creneaux = new ArrayList<>();
        long id = 1;
        for (int jour = 0; jour < jours; jour++) {
            LocalDate date = JOUR_1.plusDays(jour);
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(10, 0), LocalTime.of(12, 0)));
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(12, 0), LocalTime.of(13, 0)));
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(13, 0), LocalTime.of(14, 0)));
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(14, 0), LocalTime.of(20, 0)));
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(20, 0), LocalTime.of(0, 0)));
        }
        return creneaux;
    }

    private static Stand stand(String id, int min, int max) {
        Stand stand = new Stand(id, id, Set.of(), min, max, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }

    /** The same cells on every day: {@code parPosition[i]} is the headcount of the i-th créneau of a day, null = closed. */
    private static List<SaisieCellule> sameCellsEveryDay(List<Creneau> creneaux, Integer... parPosition) {
        List<SaisieCellule> cellules = new ArrayList<>();
        for (int index = 0; index < creneaux.size(); index++) {
            cellules.add(new SaisieCellule(creneaux.get(index).getId(), parPosition[index % parPosition.length]));
        }
        return cellules;
    }

    private static RapportOuvertures relire(Stand stand, List<Creneau> creneaux) {
        HoraireStandResolver.apply(List.of(stand), creneaux);
        return OuvertureStandsAnalyzer.analyze(List.of(stand), creneaux);
    }

    private static List<CelluleCreneau> cellulesLues(RapportOuvertures rapport, int jour) {
        return rapport.stands().get(0).jours().get(jour).creneaux();
    }

    @Test
    void unMotifRepeteDevientUneRegleTousLesJoursAuxBornesDerivees() {
        List<Creneau> creneaux = grille(12);
        Stand stand = stand("BOURSE", 1, 1);

        LigneGrille ligne = GrilleHorairesStands.apply(stand, creneaux, sameCellsEveryDay(creneaux, 2, null, null, 4, 4));

        assertThat(ligne.compacte()).isTrue();
        assertThat(ligne.regles()).isEqualTo(1);
        assertThat(ligne.exceptions()).isZero();
        assertThat(stand.getEffectifMin()).isEqualTo(2);
        assertThat(stand.getEffectifMax()).isEqualTo(4);
        HoraireStand regle = stand.getHoraires().get(0);
        assertThat(regle.getJours()).isEqualTo(TypeJoursHoraire.TOUS);
        assertThat(regle.getMode()).isEqualTo(ModeHoraire.OUVERTURE);
        // 14-20 and 20-00 at the same headcount are one window, open-ended:
        // the minimum is inherited, the afternoon names its four.
        assertThat(regle.getFenetres()).containsExactly(
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), null),
                new FenetreHoraire(LocalTime.of(14, 0), null, 4));
    }

    @Test
    void laGrilleRelueEstLaGrilleSaisie() {
        List<Creneau> creneaux = grille(3);
        Stand stand = stand("RELU", 1, 1);
        List<SaisieCellule> saisie = sameCellsEveryDay(creneaux, 2, null, 1, 4, 4);

        GrilleHorairesStands.apply(stand, creneaux, saisie);
        RapportOuvertures rapport = relire(stand, creneaux);

        for (int jour = 0; jour < 3; jour++) {
            assertThat(cellulesLues(rapport, jour))
                    .extracting(CelluleCreneau::effectif)
                    .containsExactly(2, null, 1, 4, 4);
            assertThat(cellulesLues(rapport, jour)).noneMatch(CelluleCreneau::partiel);
        }
    }

    @Test
    void saisirDeuxFoisLaMemeGrilleEcritLaMemeChose() {
        List<Creneau> creneaux = grille(4);
        Stand stand = stand("IDEM", 1, 1);
        List<SaisieCellule> saisie = sameCellsEveryDay(creneaux, 2, 2, null, 3, null);

        GrilleHorairesStands.apply(stand, creneaux, saisie);
        List<HoraireStand> premiere = List.copyOf(stand.getHoraires());
        int exceptions = stand.getOuvertures().size() + stand.getIndisponibilites().size();
        GrilleHorairesStands.apply(stand, creneaux, saisie);

        assertThat(stand.getHoraires()).hasSameSizeAs(premiere);
        assertThat(stand.getHoraires().get(0).getFenetres()).isEqualTo(premiere.get(0).getFenetres());
        assertThat(stand.getOuvertures().size() + stand.getIndisponibilites().size()).isEqualTo(exceptions);
    }

    @Test
    void unJourFermeResteFermeEtDevientLExceptionDuMotif() {
        List<Creneau> creneaux = grille(4);
        Stand stand = stand("REPOS", 1, 1);
        List<SaisieCellule> saisie = new ArrayList<>(sameCellsEveryDay(creneaux, 1, 1, 1, 1, 1));
        // Day 3 (index 2) closed on every créneau.
        for (int index = 10; index < 15; index++) {
            saisie.set(index, new SaisieCellule(creneaux.get(index).getId(), null));
        }

        LigneGrille ligne = GrilleHorairesStands.apply(stand, creneaux, saisie);
        RapportOuvertures rapport = relire(stand, creneaux);

        assertThat(ligne.regles()).isEqualTo(1);
        assertThat(ligne.exceptions()).isEqualTo(1);
        assertThat(rapport.stands().get(0).jours().get(2).etat()).isEqualTo(OuvertureStandsAnalyzer.EtatOuverture.FERME);
        assertThat(cellulesLues(rapport, 2)).extracting(CelluleCreneau::effectif).containsOnlyNulls();
        assertThat(cellulesLues(rapport, 1)).extracting(CelluleCreneau::effectif).containsExactly(1, 1, 1, 1, 1);
    }

    @Test
    void unStandFermePartoutEstFermePartoutEtGardeSesBornes() {
        List<Creneau> creneaux = grille(3);
        Stand stand = stand("VIDE", 2, 5);

        LigneGrille ligne = GrilleHorairesStands.apply(stand, creneaux, sameCellsEveryDay(creneaux, (Integer) null));
        RapportOuvertures rapport = relire(stand, creneaux);

        assertThat(ligne.effectifMin()).isEqualTo(2);
        assertThat(ligne.effectifMax()).isEqualTo(5);
        // Not "nothing stated" — that would mean open all day.
        assertThat(rapport.stands().get(0).postes()).isZero();
        assertThat(stand.getHoraires()).singleElement().satisfies(regle ->
                assertThat(regle.getMode()).isEqualTo(ModeHoraire.FERMETURE));
    }

    @Test
    void uneFenetreQuiNeSuivaitPasLesCreneauxEstAligneeSurEux() {
        List<Creneau> creneaux = grille(2);
        Stand stand = stand("DECALE", 1, 1);
        stand.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(10, 0), LocalTime.of(11, 0), null));
        RapportOuvertures avant = relire(stand, creneaux);
        assertThat(cellulesLues(avant, 0).get(0).partiel()).isTrue();

        GrilleHorairesStands.apply(stand, creneaux, sameCellsEveryDay(creneaux, 1, null, null, null, null));
        RapportOuvertures apres = relire(stand, creneaux);

        assertThat(cellulesLues(apres, 0).get(0)).isEqualTo(new CelluleCreneau(1L, 1, false, false));
        assertThat(apres.stands().get(0).jours().get(0).minutesOuvertes()).isEqualTo(120);
    }

    @Test
    void unMotifIsoleResteDateSansBloquerLaSauvegarde() {
        List<Creneau> creneaux = grille(3);
        Stand stand = stand("UNIQUE", 1, 1);
        // Three days, three different profiles: nothing repeats.
        List<SaisieCellule> saisie = new ArrayList<>();
        Integer[][] profils = {{1, null, null, null, null}, {null, 2, null, null, null}, {null, null, null, 3, 3}};
        for (int index = 0; index < creneaux.size(); index++) {
            saisie.add(new SaisieCellule(creneaux.get(index).getId(), profils[index / 5][index % 5]));
        }

        LigneGrille ligne = GrilleHorairesStands.apply(stand, creneaux, saisie);

        assertThat(ligne.compacte()).isFalse();
        assertThat(ligne.regles()).isZero();
        assertThat(ligne.exceptions()).isEqualTo(3);
        assertThat(cellulesLues(relire(stand, creneaux), 2)).extracting(CelluleCreneau::effectif)
                .containsExactly(null, null, null, 3, 3);
    }

    @Test
    void refuseUnCreneauInconnuEtUnEffectifNul() {
        List<Creneau> creneaux = grille(1);
        Stand stand = stand("KO", 1, 1);

        assertThatThrownBy(() -> GrilleHorairesStands.apply(stand, creneaux, List.of(new SaisieCellule(99L, 1))))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("99");
        assertThatThrownBy(() -> GrilleHorairesStands.apply(stand, creneaux, List.of(new SaisieCellule(1L, 0))))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("vide");
    }
}
