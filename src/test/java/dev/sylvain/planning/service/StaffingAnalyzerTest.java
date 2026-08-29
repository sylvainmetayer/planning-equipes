package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.StaffingAnalyzer.BorneRetenue;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.service.StaffingAnalyzer.CompetenceStaffing;
import dev.sylvain.planning.service.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.StaffingAnalyzer.TypologieStaffing;

class StaffingAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final List<TypologieItem> TYPOLOGIES = List.of(
            new TypologieItem("JEUX", "Jeux de société"),
            new TypologieItem("ESCAPE", "Escape game"),
            new TypologieItem("NINJA", "Ninja", true));

    private final StaffingAnalyzer analyzer = new StaffingAnalyzer();

    @Test
    void emptyProblemNeedsNobody() {
        StaffingSummary summary = analyzer.analyze(List.of(), List.of(), TYPOLOGIES, 48 * 60, 30);

        assertThat(summary.minimumTotal()).isZero();
        assertThat(summary.parJour()).isEmpty();
        assertThat(summary.jourCritique()).isNull();
    }

    @Test
    void peakCountsSimultaneousSeatsOnly() {
        // Two stands of 2 seats open at the same time: 4 people at once. A
        // third stand opens only once they have closed, and needs nobody more.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau afternoon = creneau(2, LocalTime.of(14, 0), LocalTime.of(16, 0));
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 2), matin, 2));
        postes.addAll(postes(stand("B", 2), matin, 2));
        postes.addAll(postes(stand("C", 3), afternoon, 3));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0);

        assertThat(summary.picSimultane()).isEqualTo(4);
        assertThat(summary.parJour()).hasSize(1);
        assertThat(summary.parJour().get(0).standsOuverts()).isEqualTo(3);
        assertThat(summary.parJour().get(0).sieges()).isEqualTo(7);
        assertThat(summary.parJour().get(0).heures()).isEqualTo(14.0);
    }

    @Test
    void overlappingRelayVacationsOfTheSameStandAreNotCountedTwice() {
        // The regression this class exists for: the browser-side estimate
        // summed effectifMin over every créneau a stand was open on, so a day
        // sliced into overlapping relay vacations counted the same stand
        // several times over. Two consecutive vacations of the same single-seat
        // stand need one person at a time (two over the day), never four.
        Stand stand = stand("A", 1);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand, creneau(1, LocalTime.of(10, 0), LocalTime.of(15, 15)), 1));
        postes.addAll(postes(stand, creneau(2, LocalTime.of(15, 0), LocalTime.of(20, 0)), 1));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0);

        // 2 only during the 15 min handover the relay pattern is built on.
        assertThat(summary.picSimultane()).isEqualTo(2);
        assertThat(summary.parJour().get(0).sieges()).isEqualTo(2);
    }

    @Test
    void breakBetweenVacationsRaisesThePeakToTheExactHeadcount() {
        // Two vacations of the same stand, back to back with no overlap: one
        // seat at a time, but the 30-minute legal break between two vacations
        // of the same person means the relay needs two distinct people.
        Stand stand = stand("A", 1);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand, creneau(1, LocalTime.of(10, 0), LocalTime.of(15, 0)), 1));
        postes.addAll(postes(stand, creneau(2, LocalTime.of(15, 0), LocalTime.of(20, 0)), 1));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 30);

        assertThat(summary.picSimultane()).isEqualTo(1);
        assertThat(summary.picAvecPause()).isEqualTo(2);
        assertThat(summary.minimumTotal()).isEqualTo(2);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.PIC_AVEC_PAUSE);
    }

    @Test
    void workloadBoundWinsWhenTheEventIsLongEnough() {
        // One seat open 10 hours a day for the 7 days of one ISO week = 70
        // person-hours, against a 20 h weekly cap: 4 people, way above the
        // peak of 1.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            Creneau creneau = new Creneau((long) jour, jour + 1, LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0), LocalTime.of(20, 0));
            postes.addAll(postes(stand("A", 1), creneau, 1));
        }

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 20 * 60, 30);

        assertThat(summary.nombreSemaines()).isEqualTo(1);
        assertThat(summary.totalDemandeHeures()).isEqualTo(70.0);
        assertThat(summary.chargeTotal()).isEqualTo(4);
        assertThat(summary.minimumTotal()).isEqualTo(4);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.CHARGE_HORAIRE);
    }

    @Test
    void aWindowCrossingMidnightStaysOnTheEveningItStartedOn() {
        Creneau soiree = creneau(1, LocalTime.of(22, 0), LocalTime.of(2, 0));
        StaffingSummary summary = analyzer.analyze(postes(stand("A", 1), soiree, 1), List.of(), TYPOLOGIES, 48 * 60, 30);

        assertThat(summary.parJour()).hasSize(1);
        assertThat(summary.parJour().get(0).date()).isEqualTo(JOUR);
        assertThat(summary.parJour().get(0).heures()).isEqualTo(4.0);
    }

    @Test
    void reserveMajeursStandsPushTheAdultShareUp() {
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Stand majeurs = stand("A", 2);
        majeurs.setReserveMajeurs(true);
        List<PosteAffectation> postes = new ArrayList<>(postes(majeurs, matin, 2));
        postes.addAll(postes(stand("B", 2), matin, 2));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0);

        // 2 adult-only seats + 1 of the 2 remaining ones = 3 of 4.
        assertThat(summary.minimumTotal()).isEqualTo(4);
        assertThat(summary.minimumMajeurs()).isEqualTo(3);
        assertThat(summary.minimumMineurs()).isEqualTo(1);
    }

    @Test
    void aTypologieIsABottleneckWhenItsBoundExceedsItsCompetentPool() {
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(postes(stand("A", 3, "ESCAPE"), matin, 3));
        postes.addAll(postes(stand("B", 1, "JEUX"), matin, 1));

        CompetenceStaffing competence = analyzer.analyze(postes,
                List.of(animateur("1", "ESCAPE"), animateur("2", "JEUX"), animateur("3", "JEUX")),
                TYPOLOGIES, 48 * 60, 0).parCompetence();

        // Sorted by shortfall: the bottleneck is what the reader must see first.
        assertThat(competence.parTypologie()).extracting(TypologieStaffing::typologie)
                .containsExactly("ESCAPE", "JEUX");
        TypologieStaffing escape = competence.parTypologie().get(0);
        assertThat(escape.label()).isEqualTo("Escape game");
        assertThat(escape.sieges()).isEqualTo(3);
        assertThat(escape.minimumTotal()).isEqualTo(3);
        assertThat(escape.specialistes()).isEqualTo(1);
        assertThat(escape.manque()).isEqualTo(2);
        // The global bound sees 4 seats for 3 animateurs and misses which one
        // is short: that gap is the whole point of the per-typologie reading.
        assertThat(competence.parTypologie().get(1).manque()).isZero();
        assertThat(competence.manqueTotal()).isEqualTo(2);
    }

    @Test
    void aNinjaCountsInTheirDeclaredTypologiesAndInTheSharedReserveOnly() {
        // A polyvalent may take any stand, so counting them as available in
        // every typologie would add the same person to every pool and hide
        // the bottleneck. They count where they declared, plus once as the
        // reserve.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(postes(stand("A", 2, "ESCAPE"), matin, 2));
        postes.addAll(postes(stand("B", 1, "NINJA"), matin, 1));

        CompetenceStaffing competence = analyzer.analyze(postes,
                List.of(animateur("1", "NINJA"), animateur("2", "NINJA", "JEUX")),
                TYPOLOGIES, 48 * 60, 0).parCompetence();

        assertThat(competence.polyvalents()).isEqualTo(2);
        assertThat(competence.typologieNinjaDefinie()).isTrue();
        assertThat(competence.parTypologie()).extracting(TypologieStaffing::typologie)
                .containsExactly("ESCAPE", "NINJA");
        assertThat(competence.parTypologie().get(0).specialistes()).isZero();
        assertThat(competence.parTypologie().get(0).manque()).isEqualTo(2);
        TypologieStaffing ninja = competence.parTypologie().get(1);
        assertThat(ninja.ninja()).isTrue();
        assertThat(ninja.specialistes()).isEqualTo(2);
        assertThat(ninja.manque()).isZero();
        // The reserve can absorb the shortfall here — one polyvalent per seat.
        assertThat(competence.manqueTotal()).isEqualTo(2);
    }

    @Test
    void aReferentialWithoutANinjaCategorySaysSoRatherThanReportingAnEmptyReserve() {
        // Nobody is polyvalent when no category carries the flag, so a reserve
        // of zero must read as "no such notion here", never as a shortage.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));

        CompetenceStaffing competence = analyzer
                .analyze(postes(stand("A", 2, "ESCAPE"), matin, 2), List.of(animateur("1", "ESCAPE")),
                        List.of(new TypologieItem("ESCAPE", "Escape game")), 48 * 60, 0)
                .parCompetence();

        assertThat(competence.typologieNinjaDefinie()).isFalse();
        assertThat(competence.polyvalents()).isZero();
        assertThat(competence.parTypologie().get(0).ninja()).isFalse();
    }

    @Test
    void seatsOfAStandProposingSeveralTypologiesBelongToNoneOfThem() {
        // Either pool staffs them, so no single typologie provably requires
        // them: claiming them for both would invent two bottlenecks.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(postes(stand("A", 2, "ESCAPE", "JEUX"), matin, 2));
        postes.addAll(postes(stand("B", 1, "JEUX"), matin, 1));

        CompetenceStaffing competence = analyzer.analyze(postes, List.of(animateur("1", "JEUX")),
                TYPOLOGIES, 48 * 60, 0).parCompetence();

        assertThat(competence.siegesNonAttribues()).isEqualTo(2);
        assertThat(competence.parTypologie()).extracting(TypologieStaffing::typologie).containsExactly("JEUX");
        assertThat(competence.parTypologie().get(0).sieges()).isEqualTo(1);
        assertThat(competence.manqueTotal()).isZero();
    }

    @Test
    void aStandProposingNoTypologieAtAllCanOnlyBeHeldByPolyvalents() {
        // The opposite of the case above, and the reason the two must not
        // share a branch: hasCompetenceFor() answers yes to polyvalents only,
        // so those seats are the tightest demand there is — they belong to the
        // ninja row, never to "no category can claim them".
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));

        CompetenceStaffing competence = analyzer
                .analyze(postes(standWithoutTypologie("A", 4), matin, 4), List.of(animateur("1", "NINJA")),
                        TYPOLOGIES, 48 * 60, 0)
                .parCompetence();

        assertThat(competence.siegesNonAttribues()).isZero();
        assertThat(competence.siegesReservesAuxPolyvalents()).isEqualTo(4);
        assertThat(competence.parTypologie()).extracting(TypologieStaffing::typologie).containsExactly("NINJA");
        assertThat(competence.parTypologie().get(0).sieges()).isEqualTo(4);
        assertThat(competence.parTypologie().get(0).minimumTotal()).isEqualTo(4);
        assertThat(competence.parTypologie().get(0).manque()).isEqualTo(3);
    }

    @Test
    void seatsNobodyIsEligibleForAreReportedWhenNoCategoryIsMarkedNinja() {
        // No category carries the flag, so nobody is polyvalent and nobody at
        // all can hold a stand that proposes nothing. There is no row to carry
        // that demand; the count is what makes it visible.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));

        CompetenceStaffing competence = analyzer
                .analyze(postes(standWithoutTypologie("A", 4), matin, 4), List.of(animateur("1", "ESCAPE")),
                        List.of(new TypologieItem("ESCAPE", "Escape game")), 48 * 60, 0)
                .parCompetence();

        assertThat(competence.typologieNinjaDefinie()).isFalse();
        assertThat(competence.siegesReservesAuxPolyvalents()).isEqualTo(4);
        assertThat(competence.siegesNonAttribues()).isZero();
        assertThat(competence.parTypologie()).isEmpty();
    }

    @Test
    void theShortfallOnTheNinjaCategoryIsIsolatedFromTheOneTheReserveCouldAbsorb() {
        // The reserve cannot be offered against its own shortage: the pool
        // that just came up short is the pool of reinforcements.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(postes(stand("A", 5, "NINJA"), matin, 5));
        postes.addAll(postes(stand("B", 1, "ESCAPE"), matin, 1));

        CompetenceStaffing competence = analyzer.analyze(postes,
                List.of(animateur("1", "NINJA"), animateur("2", "NINJA"), animateur("3", "NINJA"),
                        animateur("4", "ESCAPE")),
                TYPOLOGIES, 48 * 60, 0).parCompetence();

        assertThat(competence.polyvalents()).isEqualTo(3);
        assertThat(competence.manqueTotal()).isEqualTo(2);
        assertThat(competence.manquePolyvalents()).isEqualTo(2);
    }

    @Test
    void withoutAnyAnimateurTheBoundsStandButNothingIsCompared() {
        // A guard of the analyzer itself: the resource never reaches this state
        // (no animateur means the problem cannot be built, hence no seat), but
        // the rule belongs here rather than in the caller — a shortfall against
        // a pool nobody has declared yet would be an artefact.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));

        CompetenceStaffing competence = analyzer
                .analyze(postes(stand("A", 3, "ESCAPE"), matin, 3), List.of(), TYPOLOGIES, 48 * 60, 0)
                .parCompetence();

        assertThat(competence.animateursTotal()).isZero();
        assertThat(competence.parTypologie().get(0).minimumTotal()).isEqualTo(3);
        assertThat(competence.parTypologie().get(0).specialistes()).isZero();
        assertThat(competence.manqueTotal()).isZero();
    }

    @Test
    void aTypologieBoundUsesTheSameThreeBoundsAsTheGlobalOne() {
        // One ESCAPE seat open 10 h a day over one ISO week: the workload
        // bound wins there too, exactly as it does globally.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            Creneau creneau = new Creneau((long) jour, jour + 1, LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0), LocalTime.of(20, 0));
            postes.addAll(postes(stand("A", 1, "ESCAPE"), creneau, 1));
        }

        TypologieStaffing escape = analyzer.analyze(postes, List.of(animateur("1", "ESCAPE")), TYPOLOGIES,
                20 * 60, 30).parCompetence().parTypologie().get(0);

        assertThat(escape.nombreSemaines()).isEqualTo(1);
        assertThat(escape.heures()).isEqualTo(70.0);
        assertThat(escape.picSimultane()).isEqualTo(1);
        assertThat(escape.chargeTotal()).isEqualTo(4);
        assertThat(escape.minimumTotal()).isEqualTo(4);
        assertThat(escape.borneRetenue()).isEqualTo(BorneRetenue.CHARGE_HORAIRE);
        assertThat(escape.manque()).isEqualTo(3);
    }

    private static Stand stand(String id, int effectifMin) {
        return new Stand(id, id, Set.of("JEUX"), effectifMin, effectifMin, false);
    }

    private static Stand stand(String id, int effectifMin, String... typologies) {
        return new Stand(id, id, Set.of(typologies), effectifMin, effectifMin, false);
    }

    /** Accepted by the CRUD and by the YAML import alike — hence worth testing. */
    private static Stand standWithoutTypologie(String id, int effectifMin) {
        return new Stand(id, id, Set.of(), effectifMin, effectifMin, false);
    }

    /** {@code ninja} is derived from the referential, exactly as the problem build does it. */
    private static Animateur animateur(String id, String... competences) {
        Animateur animateur = new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
        Map<String, NiveauCompetence> declarees = new LinkedHashMap<>();
        for (String competence : competences) {
            declarees.put(competence, NiveauCompetence.AUTONOME);
        }
        animateur.setCompetences(declarees);
        animateur.applyNinjaTypologie(TYPOLOGIES.stream().filter(TypologieItem::ninja).map(TypologieItem::id)
                .findFirst().orElse(null));
        return animateur;
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, JOUR, debut, fin);
    }

    /** One poste per seat, exactly like {@code PlanningService#buildPostes} generates them. */
    private static List<PosteAffectation> postes(Stand stand, Creneau creneau, int seats) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (int seat = 0; seat < seats; seat++) {
            postes.add(new PosteAffectation(stand.getId() + "-" + creneau.getId() + "-" + seat, stand, creneau));
        }
        return postes;
    }
}
