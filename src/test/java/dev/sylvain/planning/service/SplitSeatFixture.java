package dev.sylvain.planning.service;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * One timeslot repaired on the day (ADR 0066), as every report reads it:
 *
 * <ul>
 * <li><b>Cirque</b>, two seats on Saturday 09:00-12:00. Ada held the first
 * one and left at 09:20: its origin {@code p1} (Ada, 09:00-09:20) and its
 * continuation {@code p1~0920} (Bob, 09:20-12:00). Cyd holds the second one,
 * {@code p2}, the whole timeslot.</li>
 * <li><b>Kiosque</b>, one seat narrowed at 09:20 and empty again:
 * {@code p3~0920}, 09:20-12:00, no origin.</li>
 * </ul>
 *
 * <p>Three places, two staffed, one to fill. Hours: Ada 0 h 20, Bob 2 h 40, Cyd
 * 3 h — six hours of seat on Cirque, two hours forty to staff on Kiosque, the
 * minutes nobody held before 09:20 counted nowhere.</p>
 */
public final class SplitSeatFixture {

    public static final LocalDate SATURDAY = LocalDate.of(2026, 7, 11);

    public final Stand circus = new Stand("CIRQUE", "Cirque", Set.of("STRATEGIE"), 2, 2, false);
    public final Stand kiosk = new Stand("KIOSQUE", "Kiosque", Set.of("AMBIANCE"), 1, 1, false);
    public final Creneau morning = new Creneau(1L, 1, SATURDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    public final Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
    public final Animateur bob = new Animateur("A-BOB", "Bob", "Kahn", LocalDate.of(1990, 1, 1), false);
    public final Animateur cyd = new Animateur("A-CYD", "Cyd", "Charisse", LocalDate.of(1990, 1, 1), false);

    /** Ada's seat, cut at 09:20. */
    public final PosteAffectation origin = new PosteAffectation("p1", circus, morning);

    /** The rest of it, Bob's. */
    public final PosteAffectation continuation = new PosteAffectation("p1~0920", circus, morning);

    /** Cyd's seat, untouched. */
    public final PosteAffectation whole = new PosteAffectation("p2", circus, morning);

    /** Kiosque's seat, narrowed to 09:20 and empty. */
    public final PosteAffectation narrowed = new PosteAffectation("p3~0920", kiosk, morning);

    public SplitSeatFixture() {
        origin.setAnimateur(ada);
        origin.setHeureFinEffective(LocalTime.of(9, 20));
        continuation.setAnimateur(bob);
        continuation.setHeureDebutEffective(LocalTime.of(9, 20));
        continuation.setSuiteDe("p1");
        whole.setAnimateur(cyd);
        narrowed.setHeureDebutEffective(LocalTime.of(9, 20));
    }

    /** The working plan: the four rows. */
    public PlanningEvenement plan() {
        PlanningEvenement planning = new PlanningEvenement();
        planning.setAnimateurs(List.of(ada, bob, cyd));
        planning.setPostes(List.of(origin, continuation, whole, narrowed));
        return planning;
    }

    /** The plan as it was published before the day: three whole seats, Ada on the first. */
    public PlanningEvenement publishedBefore() {
        PosteAffectation p1 = new PosteAffectation("p1", circus, morning);
        p1.setAnimateur(ada);
        PosteAffectation p2 = new PosteAffectation("p2", circus, morning);
        p2.setAnimateur(cyd);
        PosteAffectation p3 = new PosteAffectation("p3", kiosk, morning);
        PlanningEvenement planning = new PlanningEvenement();
        planning.setAnimateurs(List.of(ada, bob, cyd));
        planning.setPostes(List.of(p1, p2, p3));
        return planning;
    }

    /** The same plan with the rest of the split seat handed back to Ada: one stretch of hers, 09:00-12:00. */
    public PlanningEvenement planKeptByItsHolder() {
        continuation.setAnimateur(ada);
        return plan();
    }
}
