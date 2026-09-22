package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.IntendanceRepasAnalyzer.FenetreIntendance;
import dev.sylvain.planning.service.analyse.IntendanceRepasAnalyzer.LigneEmplacement;
import dev.sylvain.planning.service.analyse.IntendanceRepasAnalyzer.RapportIntendance;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * « Combien de sandwichs, et où les porter » (issue #598): the same meal
 * breaks {@link PauseAnalyzer} names person by person, counted hour by hour and
 * per emplacement.
 */
class IntendanceRepasAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final List<FenetreRepas> MIDI =
            List.of(new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60, true));

    private final IntendanceRepasAnalyzer analyzer = new IntendanceRepasAnalyzer();

    IntendanceRepasAnalyzerTest() {
        analyzer.pauseAnalyzer = new PauseAnalyzer();
    }

    @Test
    void comptelesPersonnesEnCoupureHeureParHeureEtParEmplacement() {
        Emplacement pavillon = emplacement(1, "Pavillon Bleu");
        Stand stand = stand("JEUX", pavillon);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        // A day straddling the window: 09:00-12:00 then 14:00-18:00 leaves the
        // whole 12:00-14:00 free. The break is read where PauseAnalyzer places
        // it — as early as the gap allows — so this screen and the Pauses
        // screen never tell two stories about the same day.
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 9, 0, 12, 0), alice),
                poste("p2", stand, creneau(2, 14, 0, 18, 0), alice),
                poste("p3", stand, creneau(1, 9, 0, 12, 0), bob),
                poste("p4", stand, creneau(2, 14, 0, 18, 0), bob));

        RapportIntendance rapport =
                analyzer.analyze(planning(List.of(alice, bob), postes), new ParametresLegaux(), MIDI);

        assertThat(rapport.pasMinutes()).isEqualTo(60);
        assertThat(rapport.journees()).hasSize(1);
        assertThat(rapport.journees().getFirst().date()).isEqualTo(JOUR);
        FenetreIntendance midi = rapport.journees().getFirst().fenetres().getFirst();
        assertThat(midi.libelle()).isEqualTo(FenetreRepas.MIDI);
        // « 12-13 » and « 13-14 »: the bands a service is organised on, not the
        // half-hours nobody splits a meal across.
        assertThat(midi.tranches()).containsExactly(LocalTime.of(12, 0), LocalTime.of(13, 0));
        assertThat(midi.total()).isEqualTo(2);
        assertThat(midi.totalMineurs()).isZero();
        LigneEmplacement ligne = midi.emplacements().getFirst();
        assertThat(ligne.emplacementNom()).isEqualTo("Pavillon Bleu");
        // Both eat 12:00-13:00, which is the first band and no other.
        assertThat(ligne.personnes()).containsExactly(2, 0);
        assertThat(ligne.total()).isEqualTo(2);
    }

    /**
     * A window that does not open on the hour is still read on the hour: the
     * intendance serves « à midi », not « à midi et quart ». The first band may
     * therefore start before the window and the last one end after it —
     * truncating either would hide whoever eats in the minutes left over.
     */
    @Test
    void lesBandesSontCaleesSurLHeureMemeQuandLaFenetreNeLEstPas() {
        List<FenetreRepas> fenetre =
                List.of(new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 15), LocalTime.of(13, 45), 60, true));
        Stand stand = stand("JEUX", emplacement(1, "Pavillon Bleu"));
        Animateur alice = adulte("alice");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 9, 0, 12, 0), alice),
                poste("p2", stand, creneau(2, 14, 0, 18, 0), alice));

        RapportIntendance rapport = analyzer.analyze(planning(List.of(alice), postes), new ParametresLegaux(), fenetre);

        assertThat(rapport.journees().getFirst().fenetres().getFirst().tranches())
                .containsExactly(LocalTime.of(12, 0), LocalTime.of(13, 0));
    }

    @Test
    void separeLesEmplacementsEtCompteLesMineursSansLesNommer() {
        Stand bleu = stand("BLEU", emplacement(1, "Pavillon Bleu"));
        Stand rouge = stand("ROUGE", emplacement(2, "Hall Rouge"));
        Animateur majeur = adulte("alice");
        Animateur mineur = mineur("chloe");
        List<PosteAffectation> postes = List.of(
                poste("p1", bleu, creneau(1, 9, 0, 12, 0), majeur),
                poste("p2", bleu, creneau(2, 14, 0, 18, 0), majeur),
                poste("p3", rouge, creneau(1, 9, 0, 12, 0), mineur),
                poste("p4", rouge, creneau(2, 14, 0, 18, 0), mineur));

        RapportIntendance rapport =
                analyzer.analyze(planning(List.of(majeur, mineur), postes), new ParametresLegaux(), MIDI);

        FenetreIntendance midi = rapport.journees().getFirst().fenetres().getFirst();
        assertThat(midi.emplacements()).hasSize(2);
        assertThat(midi.total()).isEqualTo(2);
        assertThat(midi.totalMineurs()).isEqualTo(1);
        LigneEmplacement hall = midi.emplacements().stream()
                .filter(ligne -> ligne.emplacementNom().equals("Hall Rouge"))
                .findFirst()
                .orElseThrow();
        assertThat(hall.totalMineurs()).isEqualTo(1);
        assertThat(hall.mineurs()).containsExactly(1, 0);
        // The CSV carries the count and the emplacement, never a name.
        assertThat(analyzer.generateCsv(rapport))
                .startsWith("jour;fenetre;emplacement;tranche;personnes;dont mineurs\n")
                .contains("2026-07-10;midi;Hall Rouge;12:00;1;1")
                .doesNotContain("chloe");
    }

    @Test
    void ditPourquoiIlNyARienPlutotQueDeMontrerZero() {
        assertThat(analyzer.analyze(null, new ParametresLegaux(), MIDI).message())
                .contains("Aucun planning résolu");
        Stand stand = stand("JEUX", emplacement(1, "Pavillon Bleu"));
        Animateur alice = adulte("alice");
        PlanningEvenement planning =
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 9, 0, 12, 0), alice)));
        assertThat(analyzer.analyze(planning, new ParametresLegaux(), List.of()).message())
                .contains("Aucune fenêtre repas déclarée");
        // A day that never reaches the window owes no meal break at all.
        assertThat(analyzer.analyze(planning, new ParametresLegaux(), MIDI).journees())
                .isEmpty();
    }

    private static PlanningEvenement planning(List<Animateur> animateurs, List<PosteAffectation> postes) {
        return new PlanningEvenement(JOUR, animateurs, postes);
    }

    private static Emplacement emplacement(long id, String nom) {
        Emplacement emplacement = new Emplacement();
        emplacement.setId(id);
        emplacement.setNom(nom);
        return emplacement;
    }

    private static Stand stand(String id, Emplacement emplacement) {
        Stand stand = new Stand(id, id, Set.of("JEUX"), 1, 2, false);
        stand.setEmplacement(emplacement);
        return stand;
    }

    private static Creneau creneau(long id, int hDebut, int mDebut, int hFin, int mFin) {
        return new Creneau(id, 1, JOUR, LocalTime.of(hDebut, mDebut), LocalTime.of(hFin, mFin));
    }

    private static Animateur adulte(String id) {
        return new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
    }

    private static Animateur mineur(String id) {
        return new Animateur(id, id, id, JOUR.minusYears(17), false);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
