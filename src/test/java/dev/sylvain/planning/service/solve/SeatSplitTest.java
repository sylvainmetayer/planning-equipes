package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seat split of ADR 0066, on hand-built seats: « maintenant » to the
 * minute, what is split and what is not, and the replay a rebuild makes of a
 * split persisted earlier.
 */
class SeatSplitTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 9, 5);

    private final Stand jeux = stand();

    private final Creneau matin = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));

    private final Animateur a87 = animateur("A87");

    @Test
    void theSplitPointIsTheMinuteNeverASecondInsideIt() {
        assertThat(SeatSplit.minute(new PastHorizon(JOUR, LocalTime.of(9, 20, 41))))
                .isEqualTo(LocalTime.of(9, 20));
    }

    /** 09:20 on 09:00–12:00: under way, split; 12:00: over; 08:59: ahead; 09:00: nobody held any of it. */
    @Test
    void onlyASeatUnderWayAndStartedBeforeTheMinuteIsSplit() {
        PosteAffectation poste = seat("P1", a87);

        assertThat(SeatSplit.needsSplit(poste, at(9, 20))).isTrue();
        assertThat(SeatSplit.needsSplit(poste, at(8, 59))).isFalse();
        assertThat(SeatSplit.needsSplit(poste, at(9, 0))).isFalse();
        assertThat(SeatSplit.needsSplit(poste, at(12, 0))).isFalse();
        assertThat(SeatSplit.isOver(poste, at(12, 0))).isTrue();
        assertThat(SeatSplit.needsSplit(poste, null)).isFalse();
    }

    /** The history keeps who held 09:00–09:20; the rest is a seat of its own, named after its origin. */
    @Test
    void theOriginKeepsWhatWasHeldAndTheRestIsASeatOfItsOwn() {
        PosteAffectation origine = seat("P1", a87);

        PosteAffectation suite = SeatSplit.split(origine, LocalTime.of(9, 20));

        assertThat(origine.heureDebutEffectif()).isEqualTo(LocalTime.of(9, 0));
        assertThat(origine.heureFinEffectif()).isEqualTo(LocalTime.of(9, 20));
        assertThat(origine.getAnimateur()).isSameAs(a87);
        assertThat(suite.getId()).isEqualTo("P1~0920");
        assertThat(suite.getSuiteDe()).isEqualTo("P1");
        assertThat(suite.heureDebutEffectif()).isEqualTo(LocalTime.of(9, 20));
        assertThat(suite.heureFinEffectif()).isEqualTo(LocalTime.of(12, 0));
        assertThat(suite.getStand()).isSameAs(jeux);
        assertThat(suite.getCreneau()).isSameAs(matin);
        assertThat(SeatSplit.isOver(origine, at(9, 21))).isTrue();
    }

    /**
     * A rebuild renumbers its seats and knows nothing of the split: the
     * persisted cell is replayed — the origin cut short and held, the rest
     * added after it with its own holder —, and the seats already started are
     * pinned as the freeze pins them.
     */
    @Test
    void aRebuildReplaysTheSplitOfThePersistedPlan() {
        Animateur remplacant = animateur("A12");
        List<PosteAffectation> postes = new ArrayList<>(List.of(seat("gen-1", null), seat("gen-2", null)));
        String cle = PlanningPersistenceService.standCreneauKey("jeux", 1L);
        Map<String, List<PlanningPersistenceService.Siege>> cells = Map.of(
                cle,
                List.of(
                        new PlanningPersistenceService.Siege(
                                "old-1", "jeux", 1L, "A87", LocalTime.of(9, 0), LocalTime.of(9, 20), null, null),
                        new PlanningPersistenceService.Siege("old-2", "jeux", 1L, "A33", null, null, null, null),
                        new PlanningPersistenceService.Siege(
                                "old-1~0920",
                                "jeux",
                                1L,
                                "A12",
                                LocalTime.of(9, 20),
                                LocalTime.of(12, 0),
                                null,
                                "old-1")));

        int ajoutes = SeatSplit.restore(postes, List.of(a87, remplacant, animateur("A33")), cells, at(10, 30));

        assertThat(ajoutes).isEqualTo(1);
        assertThat(postes).extracting(PosteAffectation::getId).containsExactly("gen-1", "gen-2", "gen-1~0920");
        assertThat(postes.get(0).heureFinEffectif()).isEqualTo(LocalTime.of(9, 20));
        assertThat(postes.get(0).getAnimateur().getId()).isEqualTo("A87");
        assertThat(postes.get(2).getSuiteDe()).isEqualTo("gen-1");
        assertThat(postes.get(2).getAnimateur()).isSameAs(remplacant);
        assertThat(postes).allSatisfy(poste -> assertThat(poste.isPasse()).isTrue());
    }

    @Test
    void nothingToReplayLeavesTheSeatsAsGenerated() {
        List<PosteAffectation> postes = new ArrayList<>(List.of(seat("gen-1", a87)));

        assertThat(SeatSplit.restore(postes, List.of(a87), Map.of(), at(10, 30)))
                .isZero();
        assertThat(postes)
                .singleElement()
                .satisfies(poste -> assertThat(poste.getSuiteDe()).isNull());
    }

    private static PastHorizon at(int heure, int minute) {
        return new PastHorizon(JOUR, LocalTime.of(heure, minute));
    }

    private PosteAffectation seat(String id, Animateur holder) {
        PosteAffectation poste = new PosteAffectation(id, jeux, matin);
        poste.setAnimateur(holder);
        return poste;
    }

    private static Stand stand() {
        Stand stand = new Stand();
        stand.setId("jeux");
        stand.setNom("Jeux");
        return stand;
    }

    private static Animateur animateur(String id) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        return animateur;
    }
}
