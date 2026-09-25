package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.FragiliteFindings;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.PosteFragile;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.SeveriteFragilite;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.CelluleMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.JourMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.TrancheMarge;
import dev.sylvain.planning.service.analyse.TensionAnalyzer.CelluleTension;
import dev.sylvain.planning.service.analyse.TensionAnalyzer.GraviteTension;
import dev.sylvain.planning.service.analyse.TensionAnalyzer.MotifTension;
import dev.sylvain.planning.service.analyse.TensionAnalyzer.RapportTension;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link TensionAnalyzer} on hand-built reports: each named rule, the
 * untruncated count of irreplaceable seats, and the empty cases.
 */
class TensionAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final LocalTime DEBUT = LocalTime.of(10, 0);
    private static final LocalTime FIN = LocalTime.of(12, 0);
    private static final Creneau CRENEAU = new Creneau(1L, 1, JOUR, DEBUT, FIN);

    @Test
    void aComfortableMarginHidingAnIrreplaceableSeatIsCritical() {
        CelluleTension cellule = seule(rate(3, 0, fragilite(poste(1L, 0))));

        assertThat(cellule.gravite()).isEqualTo(GraviteTension.CRITIQUE);
        assertThat(cellule.motifs()).containsExactly(MotifTension.SIEGE_IRREMPLACABLE);
        assertThat(cellule.siegesIrremplacables()).isEqualTo(1);
        assertThat(cellule.animateursIrremplacables()).containsExactly("alice");
    }

    @Test
    void aZeroMarginWithNoEmptySeatIsCalm() {
        CelluleTension cellule = seule(rate(0, 0, fragilite()));

        assertThat(cellule.gravite()).isEqualTo(GraviteTension.CALME);
        assertThat(cellule.motifs()).isEmpty();
    }

    @Test
    void emptySeatsNobodyCanCoverAreCritical() {
        CelluleTension cellule = seule(rate(-1, 2, fragilite()));

        assertThat(cellule.gravite()).isEqualTo(GraviteTension.CRITIQUE);
        assertThat(cellule.motifs()).containsExactly(MotifTension.SIEGES_VIDES_NON_COUVRABLES);
    }

    @Test
    void aZeroMarginWithEmptySeatsIsHigh() {
        CelluleTension cellule = seule(rate(0, 1, fragilite()));

        assertThat(cellule.gravite()).isEqualTo(GraviteTension.ELEVEE);
        assertThat(cellule.motifs()).containsExactly(MotifTension.MARGE_NULLE_AVEC_SIEGES_VIDES);
    }

    @Test
    void moreFragileSeatsThanPeopleToSpareIsHighFewerIsWatched() {
        FragiliteFindings deux = fragilite(poste(1L, 1), poste(1L, 1));

        assertThat(seule(rate(1, 0, deux)).gravite()).isEqualTo(GraviteTension.ELEVEE);
        assertThat(seule(rate(1, 0, deux)).motifs()).containsExactly(MotifTension.FRAGILES_AU_DELA_DE_LA_MARGE);
        assertThat(seule(rate(3, 0, deux)).gravite()).isEqualTo(GraviteTension.SURVEILLEE);
        assertThat(seule(rate(3, 0, deux)).motifs()).containsExactly(MotifTension.SIEGES_FRAGILES);
        // Two substitutes: not fragile at all.
        assertThat(seule(rate(0, 0, fragilite(poste(1L, 2)))).gravite()).isEqualTo(GraviteTension.CALME);
    }

    @Test
    void aStandNobodyIsCompetentForIsCriticalAOneSpecialistStandWithoutReinforcementIsWatched() {
        CelluleTension sans = seule(rate(5, 0, withRares(rare(0, 0))));
        CelluleTension unique = seule(rate(5, 0, withRares(rare(1, 0))));
        CelluleTension renforce = seule(rate(5, 0, withRares(rare(1, 2))));

        assertThat(sans.gravite()).isEqualTo(GraviteTension.CRITIQUE);
        assertThat(sans.motifs()).containsExactly(MotifTension.STAND_SANS_SPECIALISTE);
        assertThat(sans.standsSansSpecialiste()).containsExactly("S1");
        assertThat(unique.gravite()).isEqualTo(GraviteTension.SURVEILLEE);
        assertThat(unique.motifs()).containsExactly(MotifTension.SPECIALISTE_UNIQUE_SANS_RENFORT);
        assertThat(renforce.gravite()).isEqualTo(GraviteTension.CALME);
        assertThat(renforce.standsSpecialisteUnique()).containsExactly("S1");
    }

    /**
     * Alice alone can hold the stand, on twenty-five days: the fragility
     * screen details twenty of her seats, the map must count all twenty-five.
     */
    @Test
    void everyIrreplaceableSeatIsCountedNeverTheTwentyTheFragilityScreenDetails() {
        Stand stand = new Stand("S1", "S1", Set.of("ESCAPE"), 1, 1, false);
        Animateur alice = new Animateur("alice", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        alice.getCompetences().put("ESCAPE", NiveauCompetence.REFERENT);
        List<Creneau> creneaux = new ArrayList<>();
        List<PosteAffectation> postes = new ArrayList<>();
        List<JourMarge> jours = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Creneau creneau = new Creneau((long) i + 1, i + 1, JOUR.plusDays(i), DEBUT, FIN);
            creneaux.add(creneau);
            PosteAffectation poste = new PosteAffectation("P" + i, stand, creneau);
            poste.setAnimateur(alice);
            postes.add(poste);
            CelluleMarge cellule = new CelluleMarge(creneau.getDate(), i + 1, DEBUT, FIN, i + 1L, 1, 1, 0, 3, 3);
            jours.add(new JourMarge(creneau.getDate(), i + 1, List.of(cellule), cellule));
        }
        PlanningEvenement plan = new PlanningEvenement(JOUR, List.of(alice), postes);
        FragiliteAnalyzer fragilite = new FragiliteAnalyzer();
        assertThat(fragilite.analyze(plan).animateurs().getFirst().postes()).hasSize(20);

        RapportTension rapport = TensionAnalyzer.compute(marge(jours, 1), fragilite.findings(plan), creneaux, null);

        assertThat(rapport.cellulesCritiques()).isEqualTo(25);
        assertThat(rapport.jours().stream()
                        .mapToInt(jour -> jour.cellules().getFirst().siegesIrremplacables())
                        .sum())
                .isEqualTo(25);
    }

    @Test
    void anEditionWithoutAnimateurSaysSoInsteadOfDrawingACalmGrid() {
        RapportTension rapport =
                TensionAnalyzer.compute(marge(List.of(jour(cellule(0, 0))), 0), fragilite(), List.of(CRENEAU), null);

        assertThat(rapport.jours()).isEmpty();
        assertThat(rapport.tranches()).isEmpty();
        assertThat(rapport.message()).contains("Aucun animateur");
    }

    @Test
    void aStartedTimeslotIsGreyedOutWithoutAGrade() {
        RapportTension rapport = TensionAnalyzer.compute(
                marge(List.of(jour(cellule(-2, 2))), 3),
                fragilite(),
                List.of(CRENEAU),
                new PastHorizon(JOUR, LocalTime.of(11, 0)));

        CelluleTension cellule = rapport.jours().getFirst().cellules().getFirst();
        assertThat(cellule.passee()).isTrue();
        assertThat(cellule.gravite()).isNull();
        assertThat(rapport.jours().getFirst().pireCellule()).isNull();
        assertThat(rapport.cellulesCritiques()).isZero();
    }

    /* -------------------------------- builders -------------------------------- */

    private static RapportTension rate(int marge, int vides, FragiliteFindings fragilite) {
        return TensionAnalyzer.compute(
                marge(List.of(jour(cellule(marge, vides))), 5), fragilite, List.of(CRENEAU), null);
    }

    private static CelluleTension seule(RapportTension rapport) {
        return rapport.jours().getFirst().cellules().getFirst();
    }

    private static CelluleMarge cellule(int marge, int vides) {
        return new CelluleMarge(JOUR, 1, DEBUT, FIN, 1L, 3, 3 - vides, vides, marge + vides, marge);
    }

    private static JourMarge jour(CelluleMarge cellule) {
        return new JourMarge(JOUR, 1, List.of(cellule), cellule);
    }

    private static RapportMarge marge(List<JourMarge> jours, int animateurs) {
        return new RapportMarge(
                Mode.APRES, List.of(new TrancheMarge(DEBUT, FIN)), jours, animateurs, 0, null, List.of(), "");
    }

    private static PosteFragile poste(long creneauId, int remplacants) {
        return new PosteFragile(
                "S1", "S1", creneauId, JOUR, 1, DEBUT, FIN, 1, false, 1, 1, 1, remplacants, remplacants == 0);
    }

    private static FragiliteFindings fragilite(PosteFragile... postes) {
        List<AnimateurFragilite> lignes = new ArrayList<>();
        for (int i = 0; i < postes.length; i++) {
            lignes.add(new AnimateurFragilite(
                    i == 0 ? "alice" : "autre-" + i,
                    "",
                    false,
                    1,
                    1,
                    postes[i].irremplacable() ? 1 : 0,
                    0,
                    SeveriteFragilite.MODEREE,
                    List.of(postes[i]),
                    0));
        }
        return new FragiliteFindings(lignes, List.of(), 1, 0, true, false);
    }

    private static FragiliteFindings withRares(CompetenceRare rare) {
        return new FragiliteFindings(List.of(), List.of(rare), 1, 0, true, false);
    }

    private static CompetenceRare rare(int specialistes, int renforts) {
        return new CompetenceRare(
                "S1",
                "S1",
                1L,
                JOUR,
                1,
                DEBUT,
                FIN,
                List.of("ESCAPE"),
                specialistes,
                specialistes == 1 ? "alice" : null,
                null,
                renforts,
                true,
                SeveriteFragilite.ELEVEE);
    }
}
