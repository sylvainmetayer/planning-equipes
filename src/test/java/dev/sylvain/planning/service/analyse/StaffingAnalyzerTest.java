package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.BorneRetenue;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.CompetenceStaffing;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.TypologieStaffing;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
            Creneau creneau = new Creneau(
                    (long) jour,
                    jour + 1,
                    LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0),
                    LocalTime.of(20, 0));
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
    void theWorkloadBoundIsProvedWeekByWeekNotOverTheWholeEvent() {
        // A full week of demand, then a single quiet day in the next one. The
        // hours of week 28 can only be covered by people working week 28: the
        // former event-wide division handed that week half of a two-week
        // capacity and lost a whole person on the way.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            Creneau creneau = new Creneau(
                    (long) jour,
                    jour + 1,
                    LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0),
                    LocalTime.of(20, 0));
            postes.addAll(postes(stand("A", 2), creneau, 2));
        }
        postes.addAll(postes(
                stand("A", 1),
                new Creneau(99L, 8, LocalDate.of(2026, 7, 13), LocalTime.of(10, 0), LocalTime.of(12, 0)),
                1));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 30);

        assertThat(summary.nombreSemaines()).isEqualTo(2);
        // 142 h over 2 x 48 h of "capacity" used to say 2 — one short of what
        // the busiest week alone requires.
        assertThat(summary.chargeTotal()).isEqualTo(3);
        assertThat(summary.semaineCritique().semaine()).isEqualTo("2026-W28");
        assertThat(summary.semaineCritique().heures()).isEqualTo(140.0);
        assertThat(summary.minimumTotal()).isEqualTo(3);
    }

    @Test
    void aWeekTheEventBarelyTouchesCannotOfferAFullWeeklyCeiling() {
        // Two event days in that ISO week: 2 x 10 h of work per person, not the
        // 48 h the weekly ceiling would allow on a full week.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 2; jour++) {
            Creneau creneau = new Creneau(
                    (long) jour,
                    jour + 1,
                    LocalDate.of(2026, 7, 20).plusDays(jour),
                    LocalTime.of(8, 0),
                    LocalTime.of(18, 0));
            postes.addAll(postes(stand("A", 3), creneau, 3));
        }

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 30);

        assertThat(summary.parSemaine()).hasSize(1);
        assertThat(summary.parSemaine().get(0).jours()).isEqualTo(2);
        assertThat(summary.parSemaine().get(0).joursTravaillables()).isEqualTo(2);
        assertThat(summary.parSemaine().get(0).capaciteHeuresParAnimateur()).isEqualTo(20.0);
        assertThat(summary.parSemaine().get(0).debut()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    void aWeekOfSevenIdenticalDaysNeedsMorePeopleThanOneOfThem() {
        // The bound the estimate was missing outright: nobody may work seven
        // days in the same ISO week (art. L3132-1), so 7 days needing 3 people
        // each are 21 person-days, and 6 workable days apiece means 4 people.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            Creneau creneau = new Creneau(
                    (long) jour,
                    jour + 1,
                    LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0),
                    LocalTime.of(12, 0));
            postes.addAll(postes(stand("A", 3), creneau, 3));
        }

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 30);

        assertThat(summary.picAvecPause()).isEqualTo(3);
        assertThat(summary.chargeTotal()).isEqualTo(1);
        assertThat(summary.parSemaine().get(0).joursPersonne()).isEqualTo(21);
        assertThat(summary.rotationTotal()).isEqualTo(4);
        assertThat(summary.minimumTotal()).isEqualTo(4);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.ROTATION_JOURS);
    }

    @Test
    void aDayLongerThanTheDailyCeilingNeedsMorePeopleThanItsPeak() {
        // One seat held continuously from 06:00 to 02:00: a single person at a
        // time, but 20 h of work in one calendar day, and nobody may work more
        // than ten (art. L3121-18).
        Stand stand = stand("A", 1);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand, creneau(1, LocalTime.of(6, 0), LocalTime.of(16, 0)), 1));
        postes.addAll(postes(stand, creneau(2, LocalTime.of(16, 0), LocalTime.of(2, 0)), 1));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0);

        assertThat(summary.picAvecPause()).isEqualTo(1);
        assertThat(summary.parJour().get(0).heures()).isEqualTo(20.0);
        assertThat(summary.parJour().get(0).minimumJour()).isEqualTo(2);
        assertThat(summary.minimumTotal()).isEqualTo(2);
    }

    @Test
    void declaredUnavailabilityRaisesTheProjectionAboveTheProvenFloor() {
        // Half the pool is off on the only event day, so a need of 4 people
        // that day requires a pool of 8 — the correction the bounds, which
        // assume everybody available every day, cannot make on their own.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<Animateur> pool = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            Animateur animateur = animateur("A" + index, "JEUX");
            if (index % 2 == 0) {
                animateur.setJoursIndisponibles(Set.of(JOUR));
            }
            pool.add(animateur);
        }

        StaffingSummary summary = analyzer.analyze(postes(stand("A", 4), matin, 4), pool, TYPOLOGIES, 48 * 60, 0);

        assertThat(summary.minimumTotal()).isEqualTo(4);
        assertThat(summary.parJour().get(0).disponibles()).isEqualTo(4);
        assertThat(summary.indisponibilitesDeclarees()).isTrue();
        assertThat(summary.minimumAvecIndisponibilites()).isEqualTo(8);
    }

    @Test
    void withoutAnyDeclaredUnavailabilityTheProjectionIsTheFloorItself() {
        // Nothing declared is not "everybody always free": it is unknown, and
        // an unknown availability must not be invented in either direction.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));

        StaffingSummary summary = analyzer.analyze(
                postes(stand("A", 4), matin, 4), List.of(animateur("1", "JEUX")), TYPOLOGIES, 48 * 60, 0);

        assertThat(summary.indisponibilitesDeclarees()).isFalse();
        assertThat(summary.minimumAvecIndisponibilites()).isEqualTo(summary.minimumTotal());
    }

    @Test
    void aWindowCrossingMidnightStaysOnTheEveningItStartedOn() {
        Creneau soiree = creneau(1, LocalTime.of(22, 0), LocalTime.of(2, 0));
        StaffingSummary summary =
                analyzer.analyze(postes(stand("A", 1), soiree, 1), List.of(), TYPOLOGIES, 48 * 60, 30);

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

        CompetenceStaffing competence = analyzer.analyze(
                        postes,
                        List.of(animateur("1", "ESCAPE"), animateur("2", "JEUX"), animateur("3", "JEUX")),
                        TYPOLOGIES,
                        48 * 60,
                        0)
                .parCompetence();

        // Sorted by shortfall: the bottleneck is what the reader must see first.
        assertThat(competence.parTypologie())
                .extracting(TypologieStaffing::typologie)
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

        CompetenceStaffing competence = analyzer.analyze(
                        postes,
                        List.of(animateur("1", "NINJA"), animateur("2", "NINJA", "JEUX")),
                        TYPOLOGIES,
                        48 * 60,
                        0)
                .parCompetence();

        assertThat(competence.polyvalents()).isEqualTo(2);
        assertThat(competence.typologieNinjaDefinie()).isTrue();
        assertThat(competence.parTypologie())
                .extracting(TypologieStaffing::typologie)
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

        CompetenceStaffing competence = analyzer.analyze(
                        postes(stand("A", 2, "ESCAPE"), matin, 2),
                        List.of(animateur("1", "ESCAPE")),
                        List.of(new TypologieItem("ESCAPE", "Escape game")),
                        48 * 60,
                        0)
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

        CompetenceStaffing competence = analyzer.analyze(
                        postes, List.of(animateur("1", "JEUX")), TYPOLOGIES, 48 * 60, 0)
                .parCompetence();

        assertThat(competence.siegesNonAttribues()).isEqualTo(2);
        assertThat(competence.parTypologie())
                .extracting(TypologieStaffing::typologie)
                .containsExactly("JEUX");
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

        CompetenceStaffing competence = analyzer.analyze(
                        postes(standWithoutTypologie("A", 4), matin, 4),
                        List.of(animateur("1", "NINJA")),
                        TYPOLOGIES,
                        48 * 60,
                        0)
                .parCompetence();

        assertThat(competence.siegesNonAttribues()).isZero();
        assertThat(competence.siegesReservesAuxPolyvalents()).isEqualTo(4);
        assertThat(competence.parTypologie())
                .extracting(TypologieStaffing::typologie)
                .containsExactly("NINJA");
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

        CompetenceStaffing competence = analyzer.analyze(
                        postes(standWithoutTypologie("A", 4), matin, 4),
                        List.of(animateur("1", "ESCAPE")),
                        List.of(new TypologieItem("ESCAPE", "Escape game")),
                        48 * 60,
                        0)
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

        CompetenceStaffing competence = analyzer.analyze(
                        postes,
                        List.of(
                                animateur("1", "NINJA"),
                                animateur("2", "NINJA"),
                                animateur("3", "NINJA"),
                                animateur("4", "ESCAPE")),
                        TYPOLOGIES,
                        48 * 60,
                        0)
                .parCompetence();

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

        CompetenceStaffing competence = analyzer.analyze(
                        postes(stand("A", 3, "ESCAPE"), matin, 3), List.of(), TYPOLOGIES, 48 * 60, 0)
                .parCompetence();

        assertThat(competence.animateursTotal()).isZero();
        assertThat(competence.parTypologie().get(0).minimumTotal()).isEqualTo(3);
        assertThat(competence.parTypologie().get(0).specialistes()).isZero();
        assertThat(competence.manqueTotal()).isZero();
    }

    @Test
    void aTypologieBoundUsesTheSameBoundsAsTheGlobalOne() {
        // One ESCAPE seat open 10 h a day over one ISO week: the workload
        // bound wins there too, exactly as it does globally.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            Creneau creneau = new Creneau(
                    (long) jour,
                    jour + 1,
                    LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0),
                    LocalTime.of(20, 0));
            postes.addAll(postes(stand("A", 1, "ESCAPE"), creneau, 1));
        }

        TypologieStaffing escape = analyzer.analyze(postes, List.of(animateur("1", "ESCAPE")), TYPOLOGIES, 20 * 60, 30)
                .parCompetence()
                .parTypologie()
                .get(0);

        assertThat(escape.nombreSemaines()).isEqualTo(1);
        assertThat(escape.heures()).isEqualTo(70.0);
        assertThat(escape.picSimultane()).isEqualTo(1);
        assertThat(escape.chargeTotal()).isEqualTo(4);
        assertThat(escape.minimumTotal()).isEqualTo(4);
        assertThat(escape.borneRetenue()).isEqualTo(BorneRetenue.CHARGE_HORAIRE);
        assertThat(escape.manque()).isEqualTo(3);
    }

    // --- Coupure repas (issue #438) ---------------------------------------

    private static final List<FenetreRepas> MIDI =
            List.of(new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60));

    /**
     * A grid that never stops between morning and afternoon cannot be staffed
     * by its peak: whoever holds both halves owes a meal break neither leaves
     * room for. The bound is 14 where the peak says 10 — the real minimum is
     * 20, so it stays a bound, and a much better one.
     */
    @Test
    void aGridWithNoRoomToEatRaisesTheFloorAboveItsPeak() {
        Creneau matin = creneau(1, LocalTime.of(8, 0), LocalTime.of(13, 0));
        Creneau apresMidi = creneau(2, LocalTime.of(13, 0), LocalTime.of(20, 0));
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 10), matin, 10));
        postes.addAll(postes(stand("B", 10), apresMidi, 10));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), MIDI);

        assertThat(summary.picSimultane()).isEqualTo(10);
        assertThat(summary.picRepas()).isEqualTo(14);
        assertThat(summary.minimumTotal()).isEqualTo(14);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.COUPURE_REPAS);
        assertThat(summary.parJour().get(0).picRepas()).isEqualTo(14);
    }

    /** The same day cut around the window: ten people eat from noon to two, nothing is inflated. */
    @Test
    void aGridThatLeavesTheWindowFreeInflatesNothing() {
        Creneau matin = creneau(1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau apresMidi = creneau(2, LocalTime.of(14, 0), LocalTime.of(20, 0));
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 10), matin, 10));
        postes.addAll(postes(stand("B", 10), apresMidi, 10));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), MIDI);

        assertThat(summary.picRepas()).isLessThanOrEqualTo(summary.picSimultane());
        assertThat(summary.minimumTotal()).isEqualTo(10);
        assertThat(summary.borneRetenue()).isNotEqualTo(BorneRetenue.COUPURE_REPAS);
    }

    /** A day that never crosses the window owes nothing, and the bound says nothing. */
    @Test
    void aDayEntirelyOnOneSideOfTheWindowOwesNothing() {
        Creneau apresMidi = creneau(1, LocalTime.of(14, 0), LocalTime.of(20, 0));

        StaffingSummary summary = analyzer.analyze(
                postes(stand("A", 10), apresMidi, 10), List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), MIDI);

        assertThat(summary.minimumTotal()).isEqualTo(10);
        assertThat(summary.borneRetenue()).isNotEqualTo(BorneRetenue.COUPURE_REPAS);
    }

    // --- Coupure repas: the seats that deny the break (issue #482) --------

    private static final List<FenetreRepas> SOIR =
            List.of(new FenetreRepas(FenetreRepas.SOIR, LocalTime.of(20, 0), LocalTime.of(21, 0), 60));

    /**
     * The grid of {@code demo-festival-2026} on 10/07, the case the grid bound
     * read far too low: an afternoon of 138 seats 14:00-20:00, an evening of
     * 27 seats 20:00-24:00, and a 20:00-21:00 window owing 60 minutes.
     *
     * <p>An evening seat covers the whole window and runs past it, so its
     * holder can never take the break and therefore never worked the
     * afternoon: the two groups are disjoint and the day needs 165 people. The
     * grid alone gave {@code (138 + 27 + 27) / 2 = 96}, under the 153 the event
     * is staffed with, and the organiser only met the wall at solve time.</p>
     */
    @Test
    void anEveningSeatSpanningTheWholeWindowBarsItsHolderFromTheAfternoon() {
        Creneau apresMidi = creneau(1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau(2, LocalTime.of(20, 0), LocalTime.MIDNIGHT);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 138), apresMidi, 138));
        postes.addAll(postes(stand("B", 27), soiree, 27));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), SOIR);

        assertThat(summary.picSimultane()).isEqualTo(138);
        assertThat(summary.picRepas()).isEqualTo(165);
        assertThat(summary.minimumTotal()).isEqualTo(165);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.COUPURE_REPAS);
    }

    /**
     * The mirror case: the seat that blocks the break is the one <em>before</em>
     * the window. Ten seats 14:00-20:30 leave their holders half an hour of a
     * window owing an hour, so none of them can hold one of the four evening
     * seats: 14 people, where the grid bound read 12.
     */
    @Test
    void anAfternoonSeatRunningIntoTheWindowBarsItsHolderFromTheEvening() {
        Creneau apresMidi = creneau(1, LocalTime.of(14, 0), LocalTime.of(20, 30));
        Creneau soiree = creneau(2, LocalTime.of(21, 0), LocalTime.MIDNIGHT);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 10), apresMidi, 10));
        postes.addAll(postes(stand("B", 4), soiree, 4));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), SOIR);

        assertThat(summary.picSimultane()).isEqualTo(10);
        assertThat(summary.picRepas()).isEqualTo(14);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.COUPURE_REPAS);
    }

    /**
     * An evening seat that leaves the window entirely free inflates nothing:
     * 21:00-24:00 against a 20:00-21:00 window owing 60 minutes is exactly the
     * break, and its holder may well have worked the afternoon.
     */
    @Test
    void aSeatStartingWhenTheWindowClosesInflatesNothing() {
        Creneau apresMidi = creneau(1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau(2, LocalTime.of(21, 0), LocalTime.MIDNIGHT);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 138), apresMidi, 138));
        postes.addAll(postes(stand("B", 27), soiree, 27));

        StaffingSummary summary = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), SOIR);

        assertThat(summary.picRepas()).isLessThanOrEqualTo(summary.picSimultane());
        assertThat(summary.borneRetenue()).isNotEqualTo(BorneRetenue.COUPURE_REPAS);
    }

    /**
     * A seat straddling the window on both sides is the unsatisfiable grid
     * {@code RepasConstraints} documents — its own holder owes a break the seat
     * forbids, and no headcount ever staffs it. The bound stays what the grid
     * alone proves rather than counting those seats on both sides at once.
     */
    @Test
    void aSeatStraddlingBothSidesIsNotCountedTwice() {
        Creneau soiree = creneau(1, LocalTime.of(19, 0), LocalTime.of(23, 0));

        StaffingSummary summary =
                analyzer.analyze(postes(stand("A", 5), soiree, 5), List.of(), TYPOLOGIES, 48 * 60, 0, List.of(), SOIR);

        // (5 before + 5 inside + 5 after) / 2, and not the 10 that counting the
        // same five seats on either side of the window would give.
        assertThat(summary.picRepas()).isEqualTo(8);
    }

    /**
     * The rule switched off, no window reaches the analyzer, and the floor is
     * exactly what it was: a rule the solver is not asked to honour must not
     * raise the number the screen tells the organiser to recruit.
     */
    @Test
    void withoutWindowsTheFloorIsUnchanged() {
        Creneau matin = creneau(1, LocalTime.of(8, 0), LocalTime.of(13, 0));
        Creneau apresMidi = creneau(2, LocalTime.of(13, 0), LocalTime.of(20, 0));
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 10), matin, 10));
        postes.addAll(postes(stand("B", 10), apresMidi, 10));

        StaffingSummary avec = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0, List.of());
        StaffingSummary sans = analyzer.analyze(postes, List.of(), TYPOLOGIES, 48 * 60, 0);

        // 120 person-hours over one day already need 12 people whatever the
        // shape: that is the floor the windows must not move.
        assertThat(avec.picRepas()).isZero();
        assertThat(avec.minimumTotal()).isEqualTo(12);
        assertThat(sans.minimumTotal()).isEqualTo(12);
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
        animateur.applyNinjaTypologie(TYPOLOGIES.stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null));
        return animateur;
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, JOUR, debut, fin);
    }

    /** One poste per seat, exactly like {@code ProblemBuilder#buildPostes} generates them. */
    private static List<PosteAffectation> postes(Stand stand, Creneau creneau, int seats) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (int seat = 0; seat < seats; seat++) {
            postes.add(new PosteAffectation(stand.getId() + "-" + creneau.getId() + "-" + seat, stand, creneau));
        }
        return postes;
    }
}
