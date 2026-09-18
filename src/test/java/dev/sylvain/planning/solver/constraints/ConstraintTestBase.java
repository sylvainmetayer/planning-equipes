package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import ai.timefold.solver.core.api.score.stream.test.SingleConstraintVerification;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

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

    protected static final ConstraintVerifier<PlanningConstraintProvider, PlanningEvenement> check =
            ConstraintVerifier.build(new PlanningConstraintProvider(), PlanningEvenement.class, PosteAffectation.class);

    // Fixed reference dates so minor/adult status and day arithmetic stay deterministic.
    // D1..D5 are consecutive days inside the same ISO week (2026-W28), so weekly
    // aggregates group them together.
    protected static final LocalDate D1 = LocalDate.of(2026, 7, 8);
    protected static final LocalDate D2 = LocalDate.of(2026, 7, 9);
    protected static final LocalDate D3 = LocalDate.of(2026, 7, 10);
    protected static final LocalDate D4 = LocalDate.of(2026, 7, 11);
    protected static final LocalDate D5 = LocalDate.of(2026, 7, 12);
    private static final LocalDate NAISSANCE_MAJEUR = LocalDate.of(2000, 1, 1);
    /** 17 years old at D1: "jeune travailleur" of the 16-to-18 bracket. */
    private static final LocalDate NAISSANCE_MINEUR = LocalDate.of(2009, 1, 1);
    /** 14 years old at D1: the stricter under-16 bracket (art. L3163-1, L3164-1, D4153-3). */
    private static final LocalDate NAISSANCE_MOINS_DE_16_ANS = LocalDate.of(2012, 1, 1);

    private final AtomicInteger posteSequence = new AtomicInteger();

    /** Selects the constraint under test by name across every family. */
    private static Constraint constraint(ConstraintFactory factory, String name) {
        return Stream.of(
                        new AffectationConstraints().define(factory),
                        new LegalConstraints().define(factory),
                        new AdHocConstraints().define(factory),
                        new VerrouillageConstraints().define(factory),
                        new RepasConstraints().define(factory),
                        new QualiteConstraints().define(factory),
                        new PreferenceConstraints().define(factory))
                .flatMap(Stream::of)
                .filter(c -> c.getConstraintRef().id().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown constraint: " + name));
    }

    protected SingleConstraintVerification<PlanningEvenement> verify(String constraintName) {
        return check.verifyThat((provider, factory) -> constraint(factory, constraintName));
    }

    // --- Timeslot factories ------------------------------------------------

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

    protected static Creneau afternoon(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(14, 0), LocalTime.of(18, 0)); // 240 min, day
    }

    protected static Creneau nuit(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(20, 0), LocalTime.of(0, 0)); // crosses midnight
    }

    protected static Creneau longDay(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(9, 0), LocalTime.of(18, 0)); // 540 min > 8h
    }

    // --- Stand factories ---------------------------------------------------

    protected static Stand stand(String id, boolean reserveMajeurs, String... typologies) {
        return new Stand(id, id, new java.util.HashSet<>(java.util.List.of(typologies)), 1, 3, reserveMajeurs);
    }

    protected static Stand standWithStrategy(String id) {
        return stand(id, false, "STRATEGIE");
    }

    protected static Stand standPremium(String id) {
        Stand stand = stand(id, false, "STRATEGIE");
        stand.setPremium(true);
        return stand;
    }

    protected static Stand standWithEmplacement(String id, Emplacement emplacement) {
        Stand stand = standWithStrategy(id);
        stand.setEmplacement(emplacement);
        return stand;
    }

    protected static Stand standEpuisant(String id) {
        Stand stand = stand(id, false, "HOMME_JEU");
        stand.setNiveauEffort(NiveauEffort.EPUISANT);
        return stand;
    }

    protected static Emplacement emplacement(String id, double latitude, double longitude) {
        return new Emplacement(id, id, latitude, longitude);
    }

    // --- Animateur factories ----------------------------------------------

    protected static Animateur animateur(String id, LocalDate naissance, Map<String, NiveauCompetence> comp) {
        Animateur a = new Animateur(id, id, id, naissance, false);
        a.setCompetences(new HashMap<>(comp));
        return a;
    }

    protected static Animateur animateurWithSouhaits(
            String id, LocalDate naissance, Map<String, NiveauCompetence> comp, String... souhaits) {
        Animateur a = animateur(id, naissance, comp);
        a.setSouhaits(new java.util.HashSet<>(java.util.List.of(souhaits)));
        return a;
    }

    protected static Animateur referentMajeur(String id) {
        return animateur(id, NAISSANCE_MAJEUR, Map.of("STRATEGIE", NiveauCompetence.REFERENT));
    }

    protected static Animateur majeurAutonome(String id) {
        return animateur(id, NAISSANCE_MAJEUR, Map.of("STRATEGIE", NiveauCompetence.AUTONOME));
    }

    /** Typologie flagged "ninja" in the referential for the tests below. */
    protected static final String TYPOLOGIE_NINJA = "JOKER";

    /**
     * Polyvalent animateur: holds the referential's ninja typologie, so the
     * solver may dispatch them on any stand and the "buffer de polyvalents"
     * constraint counts them. Mirrors what
     * {@link Animateur#applyNinjaTypologie(String)} derives at load time.
     */
    protected static Animateur ninja(String id) {
        Animateur a = animateur(id, NAISSANCE_MAJEUR, Map.of(TYPOLOGIE_NINJA, NiveauCompetence.AUTONOME));
        a.applyNinjaTypologie(TYPOLOGIE_NINJA);
        return a;
    }

    /** Minor of the 16-to-18 bracket (8 h/day, night from 22:00, 12 h daily rest). */
    protected static Animateur mineurDebutant(String id) {
        return animateur(id, NAISSANCE_MINEUR, Map.of("STRATEGIE", NiveauCompetence.DEBUTANT));
    }

    /** Minor under 16 (7 h/day, night from 20:00, 14 h daily rest). */
    protected static Animateur under16DebutantMineur(String id) {
        return animateur(id, NAISSANCE_MOINS_DE_16_ANS, Map.of("STRATEGIE", NiveauCompetence.DEBUTANT));
    }

    // --- Poste factory -----------------------------------------------------

    protected PosteAffectation poste(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation p = new PosteAffectation("P" + posteSequence.incrementAndGet(), stand, creneau);
        p.setAnimateur(animateur);
        return p;
    }

    /**
     * A seat of a timeslot already started when the problem was built (ADR
     * 0044): counted by every rule, reproached by none. Marked, not pinned —
     * the analyses of the persisted plan mark without pinning, and the rule
     * has to hold there too.
     */
    protected PosteAffectation postePasse(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation p = poste(stand, creneau, animateur);
        p.setPasse(true);
        return p;
    }

    /**
     * A poste covering only part of {@code creneau} — the case created by a
     * partial stand closure (issue #60): {@code creneau} is still the real,
     * persisted créneau, but {@link PosteAffectation#getHeureDebutEffective()}
     * narrows the time this specific poste actually spans.
     */
    protected PosteAffectation posteWithEffectiveFenetre(
            Stand stand, Creneau creneau, Animateur animateur, LocalTime debutEffectif, LocalTime finEffective) {
        PosteAffectation p = poste(stand, creneau, animateur);
        p.setHeureDebutEffective(debutEffectif);
        p.setHeureFinEffective(finEffective);
        return p;
    }
}
