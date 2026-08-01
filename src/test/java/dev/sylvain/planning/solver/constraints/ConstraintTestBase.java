package dev.sylvain.planning.solver.constraints;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import ai.timefold.solver.test.api.score.stream.SingleConstraintVerification;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;
import dev.sylvain.planning.solver.PlanningConstraintProvider;

/**
 * Shared plumbing for the per-constraint unit tests. Each constraint is
 * exercised in isolation with Timefold's {@link ConstraintVerifier}: fast, no
 * Quarkus context and no database, so the whole suite runs in milliseconds.
 *
 * <p>Constraints live in private methods of the family classes (see
 * {@link PlanningConstraintProvider}), so {@link #constraint} resolves the one
 * under test by name from the family's {@code define(...)} output — this keeps
 * the tests decoupled from the ordering of the constraint array and requires no
 * change to production visibility.</p>
 */
abstract class ConstraintTestBase {

    protected static final ConstraintVerifier<PlanningConstraintProvider, PlanningFestival> verifier =
            ConstraintVerifier.build(new PlanningConstraintProvider(), PlanningFestival.class, PosteAffectation.class);

    // Fixed reference dates so minor/adult status and day arithmetic stay deterministic.
    protected static final LocalDate D1 = LocalDate.of(2026, 7, 8);
    protected static final LocalDate D2 = LocalDate.of(2026, 7, 9);
    private static final LocalDate NAISSANCE_MAJEUR = LocalDate.of(2000, 1, 1);
    private static final LocalDate NAISSANCE_MINEUR = LocalDate.of(2012, 1, 1);

    private final AtomicInteger posteSequence = new AtomicInteger();

    /** Selects the constraint under test by name across every family. */
    private static Constraint constraint(ConstraintFactory factory, String name) {
        return Stream.of(
                        new AffectationConstraints().define(factory),
                        new LegalConstraints().define(factory),
                        new AdHocConstraints().define(factory),
                        new QualiteConstraints().define(factory),
                        new PreferenceConstraints().define(factory))
                .flatMap(Stream::of)
                .filter(c -> c.getConstraintName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown constraint: " + name));
    }

    protected SingleConstraintVerification<PlanningFestival> verify(String constraintName) {
        return verifier.verifyThat((provider, factory) -> constraint(factory, constraintName));
    }

    // --- Créneau factories -------------------------------------------------

    // Creneau.id is a numeric surrogate in production; tests keep their
    // readable String labels ("J1-MATIN"...) and map each distinct label to a
    // stable synthetic Long id, so none of the many call sites below need to
    // change.
    private static final Map<String, Long> CRENEAU_ID_POOL = new ConcurrentHashMap<>();
    private static final AtomicLong CRENEAU_ID_SEQUENCE = new AtomicLong();

    private static Long idCreneau(String label) {
        return CRENEAU_ID_POOL.computeIfAbsent(label, k -> CRENEAU_ID_SEQUENCE.incrementAndGet());
    }

    protected static Creneau creneau(String id, int jour, LocalDate date, LocalTime debut, LocalTime fin) {
        return new Creneau(idCreneau(id), jour, date, debut, fin);
    }

    protected static Creneau matin(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(9, 0), LocalTime.of(13, 0)); // 240 min, day
    }

    protected static Creneau apresMidi(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(14, 0), LocalTime.of(18, 0)); // 240 min, day
    }

    protected static Creneau nuit(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(20, 0), LocalTime.of(0, 0)); // crosses midnight
    }

    protected static Creneau journeeLongue(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(9, 0), LocalTime.of(18, 0)); // 540 min > 8h
    }

    // --- Stand factories ---------------------------------------------------

    protected static Stand stand(String id, boolean reserveMajeurs, TypologieJeu... typologies) {
        return new Stand(id, id, new java.util.HashSet<>(java.util.List.of(typologies)),
                1, 3, reserveMajeurs);
    }

    protected static Stand standStrategie(String id) {
        return stand(id, false, TypologieJeu.STRATEGIE);
    }

    protected static Stand standPremium(String id) {
        Stand stand = stand(id, false, TypologieJeu.STRATEGIE);
        stand.setPremium(true);
        return stand;
    }

    protected static Stand standAvecEmplacement(String id, Emplacement emplacement) {
        Stand stand = standStrategie(id);
        stand.setEmplacement(emplacement);
        return stand;
    }

    protected static Emplacement emplacement(String id, double latitude, double longitude) {
        return new Emplacement(id, id, latitude, longitude);
    }

    // --- Animateur factories ----------------------------------------------

    protected static Animateur animateur(String id, LocalDate naissance, Map<TypologieJeu, NiveauCompetence> comp) {
        Animateur a = new Animateur(id, id, id, naissance, false);
        a.setCompetences(new HashMap<>(comp));
        return a;
    }

    protected static Animateur majeurReferent(String id) {
        return animateur(id, NAISSANCE_MAJEUR, Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.REFERENT));
    }

    protected static Animateur majeurAutonome(String id) {
        return animateur(id, NAISSANCE_MAJEUR, Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.AUTONOME));
    }

    protected static Animateur mineurDebutant(String id) {
        return animateur(id, NAISSANCE_MINEUR, Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.DEBUTANT));
    }

    // --- Poste factory -----------------------------------------------------

    protected PosteAffectation poste(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation p = new PosteAffectation("P" + posteSequence.incrementAndGet(), stand, creneau);
        p.setAnimateur(animateur);
        return p;
    }
}
