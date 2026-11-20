package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.constraints.ExclusionEligibilite;
import dev.sylvain.planning.solver.handsearch.HandSearch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * What the tests of the direct engine share: a scenario loaded and prepared
 * as a solve prepares it, without a database; the branches no versioned
 * scenario carries, fabricated on it; plans to score; and the two scores side
 * by side.
 */
final class HandSearchFixtures {

    private HandSearchFixtures() {}

    /** A prepared problem and the configuration it is solved and refereed under. */
    record Loaded(PlanningEvenement problem, SolverConfiguration configuration) {

        /** The constraint provider's score of the plan the problem carries, by constraint: points lost. */
        Map<String, Long> refereeBreakdown() {
            Map<String, Long> points = new TreeMap<>();
            for (ConstraintContribution contribution :
                    configuration.diagnosticService().analyze(problem).contributions()) {
                HardMediumSoftScore score = contribution.score();
                points.merge(
                        contribution.constraintName(),
                        -(score.hardScore() + score.mediumScore() + score.softScore()),
                        Long::sum);
            }
            return points;
        }

        /**
         * The rules the two engines do not score alike on the plan the problem
         * carries, each with « own / referee »; empty when they agree on every
         * constraint of the catalogue.
         */
        Map<String, String> disagreements() {
            Map<String, Long> referee = refereeBreakdown();
            Map<String, Long> own = HandSearch.breakdown(problem);
            Map<String, String> differing = new TreeMap<>();
            for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
                long theirs = referee.getOrDefault(definition.name(), 0L);
                Long mine = own.get(definition.name());
                if (mine == null || mine != theirs) {
                    differing.put(definition.name(), mine + " / " + theirs);
                }
            }
            return differing;
        }
    }

    /**
     * A scenario prepared like a solve prepares it. {@code scenario} names a
     * file of {@code src/main/resources/scenarios/}, or any YAML by its
     * absolute path.
     */
    static Loaded load(String scenario) {
        EmptyReferenceData referenceData = new EmptyReferenceData();
        SolverConfiguration configuration = new SolverConfiguration(
                900L,
                300L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceData,
                ConfigProvider.getConfig());
        ScenarioYamlReader.ScenarioImporte imported;
        try {
            Path path = Path.of(scenario);
            ScenarioDto dto = path.isAbsolute() && Files.isRegularFile(path)
                    ? ScenarioYamlReader.parseScenarioText(Files.readString(path, StandardCharsets.UTF_8))
                    : ScenarioYamlReader.readScenario(ScenarioYamlReader.scenarioPath(scenario));
            imported = ScenarioYamlReader.fromDto(dto, referenceData::getParametresLegaux);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PlanningEvenement problem = imported.planning();
        imported.sections().contraintes().ifPresent(contraintes -> {
            List<ConstraintToggle> toggles = new ArrayList<>();
            contraintes.desactivees().forEach(nom -> toggles.add(new ConstraintToggle(nom, false)));
            contraintes.activees().forEach(nom -> toggles.add(new ConstraintToggle(nom, true)));
            problem.setConstraintsDesactivees(toggles);
        });
        new SolveRunner(configuration, referenceData, null).prepareProblem(problem);
        imported.sections().parametresQualite().ifPresent(qualite -> problem.setParametresQualite(List.of(qualite)));
        FrozenPast.pin(problem.getPostes());
        return new Loaded(problem, configuration);
    }

    /** Searches for {@code seconds} with the direct engine and leaves its plan on the problem. */
    static HardMediumSoftScore search(PlanningEvenement problem, long seconds, long seed) {
        return new HandSearch(problem)
                .solve(new HandSearch.Limits(seconds, 0, 0, seed), () -> false, (millis, score, lay) -> {});
    }

    /* ------------------------------- plans ------------------------------- */

    private static boolean movable(PosteAffectation poste) {
        return !poste.isVerrouille() && !poste.isPasse();
    }

    static void emptyPlan(PlanningEvenement problem) {
        problem.getPostes().stream().filter(HandSearchFixtures::movable).forEach(poste -> poste.setAnimateur(null));
    }

    /** Anybody on any seat, about one seat in seven left empty: double bookings and breaches of every kind. */
    static void randomPlan(PlanningEvenement problem, SplittableRandom random) {
        List<Animateur> animateurs = problem.getAnimateurs();
        for (PosteAffectation poste : problem.getPostes()) {
            if (movable(poste)) {
                poste.setAnimateur(random.nextInt(100) < 15 ? null : animateurs.get(random.nextInt(animateurs.size())));
            }
        }
    }

    /** Random again, but nobody twice at once: what a plan near feasibility looks like. */
    static void sparsePlan(PlanningEvenement problem, SplittableRandom random) {
        emptyPlan(problem);
        List<Animateur> animateurs = problem.getAnimateurs();
        for (PosteAffectation poste : problem.getPostes()) {
            Animateur candidate = animateurs.get(random.nextInt(animateurs.size()));
            boolean free = movable(poste) && random.nextInt(100) >= 10;
            for (PosteAffectation other : problem.getPostes()) {
                if (!free) {
                    break;
                }
                free = other == poste
                        || !candidate.equals(other.getAnimateur())
                        || !other.getCreneau()
                                .getDate()
                                .equals(poste.getCreneau().getDate())
                        || !poste.overlaps(other);
            }
            if (free) {
                poste.setAnimateur(candidate);
            }
        }
    }

    /** Every movable seat on one of three people: every ceiling broken, week after week. */
    static void overloadedPlan(PlanningEvenement problem) {
        List<Animateur> animateurs = problem.getAnimateurs();
        int next = 0;
        for (PosteAffectation poste : problem.getPostes()) {
            if (movable(poste)) {
                poste.setAnimateur(animateurs.get(next++ % Math.min(3, animateurs.size())));
            }
        }
    }

    /**
     * Nobody anywhere but on the remainders of split seats: a stand held by
     * remainders alone has no crew to compare its faces to.
     */
    static void remaindersOnlyPlan(PlanningEvenement problem, SplittableRandom random) {
        emptyPlan(problem);
        List<Animateur> animateurs = problem.getAnimateurs();
        for (PosteAffectation poste : problem.getPostes()) {
            if (movable(poste) && poste.getSuiteDe() != null) {
                poste.setAnimateur(animateurs.get(random.nextInt(animateurs.size())));
            }
        }
    }

    /** One movable seat in ten handed to anybody. */
    static void perturb(PlanningEvenement problem, SplittableRandom random) {
        List<Animateur> animateurs = problem.getAnimateurs();
        for (PosteAffectation poste : problem.getPostes()) {
            if (movable(poste) && random.nextInt(100) < 10) {
                poste.setAnimateur(animateurs.get(random.nextInt(animateurs.size())));
            }
        }
    }

    /* ------------------------- fabricated branches ------------------------- */

    /**
     * Lays on a problem <b>carrying a plan</b> the branches no versioned
     * scenario has: locks on an animateur and on an animateur × timeslot,
     * pinned seats, a first day already past, a published plan, stands a kilometre apart, seats split on
     * the day — on a premium stand, and after midnight on a timeslot that crosses it —, game-category ceilings, the five kinds of ad hoc exception —
     * one forced assignment on the past day —, the supervision of minors and
     * the hard run of consecutive days switched on, birthdays inside the
     * event, and two birth dates nobody knows. Then empties every seat that is neither pinned nor past.
     *
     * <p>The case is over-constrained on purpose and often has no feasible
     * plan: it is there to be scored, not solved.</p>
     */
    static void fabricateBranches(PlanningEvenement problem, SplittableRandom random) {
        List<PosteAffectation> postes = problem.getPostes();
        List<Animateur> animateurs = problem.getAnimateurs();
        List<LocalDate> days = postes.stream()
                .map(poste -> poste.getCreneau().getDate())
                .distinct()
                .sorted()
                .toList();
        int last = days.size() - 1;
        LocalDate pastDay = days.size() > 1 ? days.get(0) : null;
        LocalDate pinnedDay = days.get(Math.min(2, last));
        LocalDate lockedDay = days.get(Math.min(3, last));
        LocalDate slotDay = days.get(Math.min(4, last));
        LocalDate splitDay = days.get(Math.min(6, last));

        List<VerrouillagePlanning> locks = new ArrayList<>();
        Animateur locked = null;
        Animateur slotLocked = null;
        for (PosteAffectation poste : postes) {
            Animateur holder = poste.getAnimateur();
            LocalDate day = poste.getCreneau().getDate();
            if (holder != null && day.equals(lockedDay) && locked == null) {
                locked = holder;
                VerrouillagePlanning lock = new VerrouillagePlanning("fixture-animateur", TypeVerrouillage.ANIMATEUR);
                lock.setAnimateurId(holder.getId());
                locks.add(lock);
            } else if (holder != null && day.equals(slotDay) && slotLocked == null && !holder.equals(locked)) {
                slotLocked = holder;
                poste.setVerrouille(true);
                VerrouillagePlanning lock =
                        new VerrouillagePlanning("fixture-creneau", TypeVerrouillage.ANIMATEUR_CRENEAU);
                lock.setAnimateurId(holder.getId());
                lock.setCreneauId(poste.getCreneau().getId());
                locks.add(lock);
            }
        }
        Set<AffectationPubliee> published = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            Animateur holder = poste.getAnimateur();
            LocalDate day = poste.getCreneau().getDate();
            int dayIndex = days.indexOf(day);
            if (day.equals(pastDay)) {
                poste.setPasse(true);
                poste.setVerrouille(true); // what FrozenPast.pin does to a seat already started
            } else if (holder != null && (holder.equals(locked) || (day.equals(pinnedDay) && random.nextInt(7) == 0))) {
                poste.setVerrouille(true);
            }
            if (holder != null && dayIndex >= 1 && dayIndex <= 5 && random.nextInt(10) != 0) {
                Creneau creneau = poste.getCreneau();
                published.add(new AffectationPubliee(
                        poste.getStand().getId(),
                        creneau.getDate(),
                        creneau.getHeureDebut(),
                        creneau.getHeureFin(),
                        holder.getId()));
            }
        }

        // seats split on the day: the origin keeps the first half, a remainder seat takes the second —
        // and the last quarter, past midnight, on a timeslot that crosses it
        List<PosteAffectation> all = new ArrayList<>(postes);
        int split = 0;
        int splitOvernight = 0;
        for (PosteAffectation poste : postes) {
            int minutes = poste.getDureeEffectiveMinutes();
            Creneau creneau = poste.getCreneau();
            boolean overnight =
                    creneau.getHeureFin() != null && creneau.getHeureFin().isBefore(creneau.getHeureDebut());
            boolean chosen = overnight
                    ? splitOvernight < 6
                    : split < 12 && creneau.getDate().equals(splitDay);
            if (chosen && minutes >= 120 && !poste.isVerrouille() && poste.getHeureFinEffective() == null) {
                if (overnight) {
                    splitOvernight++;
                } else if (split == 0) {
                    poste.getStand().setPremium(true);
                }
                all.add(splitAfter(poste, overnight ? minutes * 3 / 4 : minutes / 2));
                split++;
            }
        }
        problem.setPostes(all);
        problem.setVerrouillages(locks);
        problem.setAffectationsPubliees(new ArrayList<>(published));

        // ceilings on the two game categories the first stands offer
        List<QuotaTypologie> quotas = new ArrayList<>();
        Set<String> capped = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            for (String typologie : poste.getStand().getTypologiesProposees()) {
                if (capped.size() < 2 && capped.add(typologie)) {
                    quotas.add(new QuotaTypologie(typologie, 2 + 2 * capped.size()));
                }
            }
        }
        problem.setQuotasTypologies(quotas);

        // ad hoc: grouped arrivals (a pair, a trio), an affinity, an incompatibility, a forced
        // unavailability, two forced assignments — one of them on the day already past
        int[] order = random.ints(0, animateurs.size())
                .distinct()
                .limit(animateurs.size())
                .toArray();
        List<ContrainteAdHoc> adHoc = new ArrayList<>(problem.getContraintesAdHoc());
        PosteAffectation lastSeat = postes.get(postes.size() - 1);
        PosteAffectation pastSeat =
                postes.stream().filter(PosteAffectation::isPasse).findFirst().orElse(postes.get(0));
        adHoc.add(adHoc("fixture-arrivee-2", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, animateurs, order, 0, 1));
        adHoc.add(adHoc(
                "fixture-arrivee-3", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, animateurs, order, 2, 3, 4));
        adHoc.add(adHoc("fixture-affinite", TypeContrainteAdHoc.AFFINITE, null, null, animateurs, order, 5, 6));
        adHoc.add(adHoc("fixture-incompat", TypeContrainteAdHoc.INCOMPATIBILITE, null, null, animateurs, order, 7, 8));
        adHoc.add(adHoc(
                "fixture-indispo",
                TypeContrainteAdHoc.INDISPONIBILITE_FORCEE,
                lastSeat.getCreneau(),
                null,
                animateurs,
                order,
                9));
        adHoc.add(adHoc(
                "fixture-forcee",
                TypeContrainteAdHoc.AFFECTATION_FORCEE,
                null,
                lastSeat.getStand(),
                animateurs,
                order,
                10));
        adHoc.add(adHoc(
                "fixture-forcee-passee",
                TypeContrainteAdHoc.AFFECTATION_FORCEE,
                pastSeat.getCreneau(),
                pastSeat.getStand(),
                animateurs,
                order,
                11));
        problem.setContraintesAdHoc(adHoc);

        // the stands scattered over four places a kilometre apart: a day across them is far, slow and scattered
        List<Stand> stands =
                postes.stream().map(PosteAffectation::getStand).distinct().toList();
        for (int i = 0; i < stands.size(); i++) {
            int place = i % 4;
            stands.get(i)
                    .setEmplacement(
                            new Emplacement("fixture-place-" + place, "Place " + place, 48.0 + place * 0.01, 2.0));
        }

        // the supervision of minors and the hard run of consecutive days, both off unless asked for
        List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees());
        for (String rule : List.of(ExclusionEligibilite.ENCADREMENT_DES_MINEURS, "maxJoursConsecutifsTravaillesDur")) {
            toggles.removeIf(toggle -> toggle.getNom().equals(rule));
            toggles.add(new ConstraintToggle(rule, true));
        }
        problem.setConstraintsDesactivees(toggles);

        // two turn 18 on the sixth day, two turn 16 on the ninth, two stay 17 throughout
        LocalDate first = days.get(0);
        int young = Math.min(6, animateurs.size() / 3);
        for (int i = 0; i < young; i++) {
            LocalDate birth = i < 2
                    ? first.plusDays(5).minusYears(18)
                    : i < 4
                            ? first.plusDays(8).minusYears(16)
                            : first.minusYears(17).minusDays(30);
            animateurs.get(order[order.length - 1 - i]).setDateNaissance(birth);
        }
        // two more whose birth date is unknown: neither minors nor adults for any rule of an age bracket
        for (int i = young; i < Math.min(young + 2, animateurs.size() / 2); i++) {
            animateurs.get(order[order.length - 1 - i]).setDateNaissance(null);
        }
        emptyPlan(problem);
    }

    /**
     * Splits a seat on the day (ADR 0066): it now ends {@code minutes} after
     * its start, and the seat returned — its remainder — takes over from there.
     */
    static PosteAffectation splitAfter(PosteAffectation poste, int minutes) {
        LocalTime at = poste.heureDebutEffectif().plusMinutes(minutes);
        PosteAffectation remainder =
                new PosteAffectation(poste.getId() + "-suite", poste.getStand(), poste.getCreneau());
        remainder.setSuiteDe(poste.getId());
        remainder.setHeureDebutEffective(at);
        remainder.setHeureFinEffective(poste.heureFinEffectif());
        poste.setHeureFinEffective(at);
        return remainder;
    }

    private static ContrainteAdHoc adHoc(
            String id,
            TypeContrainteAdHoc type,
            Creneau creneau,
            Stand stand,
            List<Animateur> animateurs,
            int[] order,
            int... members) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, type);
        List<Animateur> concerned = new ArrayList<>();
        for (int member : members) {
            concerned.add(animateurs.get(order[member % order.length]));
        }
        contrainte.setAnimateursConcernes(concerned);
        contrainte.setCreneau(creneau);
        contrainte.setStand(stand);
        return contrainte;
    }
}
