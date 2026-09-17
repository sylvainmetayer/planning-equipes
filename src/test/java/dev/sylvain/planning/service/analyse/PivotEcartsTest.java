package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PivotEcarts.Axe;
import dev.sylvain.planning.service.analyse.PivotEcarts.Cellule;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * « Où se concentrent les écarts » (issue #496): the same matches the
 * Contraintes screen counts, tallied per day, per stand and per animateur.
 */
class PivotEcartsTest {

    private static final LocalDate LUNDI = LocalDate.of(2026, 7, 6);
    private static final LocalDate MARDI = LocalDate.of(2026, 7, 7);

    @Test
    void compteUnSiegeSurLesTroisAxesALaFois() {
        Stand stand = stand("JEUX");
        Animateur alice = animateur("alice");

        List<Cellule> cellules = PivotEcarts.of(Map.of(
                "posteDoitEtrePourvu", List.of(new MatchFacts(List.of(poste(stand, creneau(1, LUNDI), alice))))));

        // Sorted by constraint, then axis in declaration order, then key: two
        // analyses of the same plan produce identical payloads.
        assertThat(cellules)
                .containsExactly(
                        new Cellule("posteDoitEtrePourvu", Axe.JOUR, "2026-07-06", 1),
                        new Cellule("posteDoitEtrePourvu", Axe.STAND, "JEUX", 1),
                        new Cellule("posteDoitEtrePourvu", Axe.ANIMATEUR, "alice", 1));
    }

    @Test
    void ditSurQuelleJourneeLesEcartsSeConcentrent() {
        // Three breaches on Monday, one on Tuesday: the cell answers in one read.
        Stand stand = stand("JEUX");
        List<MatchFacts> matches = List.of(
                new MatchFacts(List.of(poste(stand, creneau(1, LUNDI), animateur("a")))),
                new MatchFacts(List.of(poste(stand, creneau(2, LUNDI), animateur("b")))),
                new MatchFacts(List.of(poste(stand, creneau(3, LUNDI), animateur("c")))),
                new MatchFacts(List.of(poste(stand, creneau(4, MARDI), animateur("d")))));

        List<Cellule> jours = PivotEcarts.of(Map.of("amplitude", matches)).stream()
                .filter(cellule -> cellule.axe() == Axe.JOUR)
                .toList();

        assertThat(jours)
                .containsExactly(
                        new Cellule("amplitude", Axe.JOUR, "2026-07-06", 3),
                        new Cellule("amplitude", Axe.JOUR, "2026-07-07", 1));
    }

    @Test
    void unMatchQuiNommePlusieursStandsCompteSurChacun() {
        // A rule grouping seats hands over a list; « combien d'écarts touchent
        // ce stand » has to count all of them, unlike the link to a fiche,
        // which must not point at the wrong one.
        List<Object> facts = List.of(List.of(
                poste(stand("BLEU"), creneau(1, LUNDI), animateur("a")),
                poste(stand("ROUGE"), creneau(1, LUNDI), animateur("a"))));

        List<Cellule> cellules = PivotEcarts.of(Map.of("emplacements", List.of(new MatchFacts(facts))));

        assertThat(cellules)
                .contains(
                        new Cellule("emplacements", Axe.STAND, "BLEU", 1),
                        new Cellule("emplacements", Axe.STAND, "ROUGE", 1))
                // The animateur is named twice by the same match: one breach for them.
                .contains(new Cellule("emplacements", Axe.ANIMATEUR, "a", 1));
    }

    @Test
    void lesDatesNuesDUneRegleHebdomadaireComptentCommeDesJours() {
        List<Cellule> cellules =
                PivotEcarts.of(Map.of("dureeHebdomadaire", List.of(new MatchFacts(List.of(animateur("a"), LUNDI)))));

        assertThat(cellules)
                .containsExactly(
                        new Cellule("dureeHebdomadaire", Axe.JOUR, "2026-07-06", 1),
                        new Cellule("dureeHebdomadaire", Axe.ANIMATEUR, "a", 1));
    }

    @Test
    void nEmetAucuneCelluleQuandRienNEstEnDefaut() {
        assertThat(PivotEcarts.of(Map.of())).isEmpty();
        // A match naming nothing the axes know produces no cell either.
        assertThat(PivotEcarts.of(Map.of("regle", List.of(new MatchFacts(List.of("un texte"))))))
                .isEmpty();
    }

    private static Stand stand(String id) {
        return new Stand(id, id, Set.of("JEUX"), 1, 2, false);
    }

    private static Creneau creneau(long id, LocalDate date) {
        return new Creneau(id, 1, date, LocalTime.of(9, 0), LocalTime.of(13, 0));
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
    }

    private static PosteAffectation poste(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation("P-" + stand.getId() + creneau.getId(), stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
