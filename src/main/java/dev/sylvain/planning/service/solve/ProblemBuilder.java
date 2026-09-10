package dev.sylvain.planning.service.solve;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.solver.constraints.AdHocConstraints;
import dev.sylvain.planning.service.ReferenceData;

/**
 * Builds the problem a solve runs on, out of the edition's reference data:
 * one seat per required place on every stand × créneau, plus the locks, the
 * ad hoc constraints and the legal parameters that shape it. Also holds the
 * two variants that do not start from scratch — the réamorçage of issue #174
 * and the incremental replanification of issue #86.
 *
 * <p>Split out of {@link PlanningService}, which keeps the public entry points
 * as a façade. The pieces that need no database stay {@code static} and
 * package-private, which is what lets {@code PlanningServicePosteGenerationTest},
 * {@code PlanningServiceReamorcageTest}, {@code PlanningServiceIncrementalTest}
 * and {@code PlanningServiceVerrouillageTest} exercise them without a
 * container.</p>
 */
public final class ProblemBuilder {

    private final ReferenceData referenceDataService;

    /**
     * The persisted plan, as a supplier rather than a field: the bean is
     * {@code @Inject}-ed into {@link PlanningService} after construction, and
     * stays {@code null} in the plain-Java harnesses that build the service
     * with {@code new} — {@link #applyVerrouillages} guards on that, as it
     * always did.
     */
    private final Supplier<PlanningPersistenceService> persistence;

    ProblemBuilder(ReferenceData referenceDataService, Supplier<PlanningPersistenceService> persistence) {
        this.referenceDataService = referenceDataService;
        this.persistence = persistence;
    }

    /**
     * Builds a fresh problem from the persisted reference data: one
     * {@link PosteAffectation} per required seat ({@code stand.effectifMax}) on
     * every stand × timeslot, all seats unassigned. This mirrors the client-side
     * builder so a solve can be launched by sending only a request to the
     * server — the (potentially huge) planning is built here and never travels
     * to the browser and back, which is what makes very large scenarios
     * solvable at all (the JSON of such a planning exceeds the HTTP body limit).
     */
    public PlanningEvenement buildFromReferenceData() {
        // Resolved stands: buildPostes asks each créneau which parts of it
        // a stand is open for, so the recurring horaires have to be expanded
        // first.
        return buildFromReferenceData(
                referenceDataService.listAnimateurs(),
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux());
    }

    /**
     * The build itself, on lists already read from the referential. Locks, ad
     * hoc constraints and legal parameters are still read from here.
     */
    private PlanningEvenement buildFromReferenceData(List<Animateur> animateurs, List<Stand> stands,
            List<Creneau> creneaux) {
        return buildFromReferenceData(animateurs, stands, creneaux, null);
    }

    /**
     * @param planPersiste the persisted assignments, when the caller has
     *                     already read them — the warm start of issue #174
     *                     needs the very same map the locks walk, and reading
     *                     it twice both costs a full read of a 3 500-seat plan
     *                     and leaves a window in which the two disagree.
     *                     {@code null} lets the locks read it themselves.
     */
    private PlanningEvenement buildFromReferenceData(List<Animateur> animateurs, List<Stand> stands,
            List<Creneau> creneaux, Map<String, List<String>> planPersiste) {
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        List<PosteAffectation> postes = buildPostes(stands, creneaux);
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        if (planPersiste == null) {
            applyVerrouillages(postes, animateurs, verrouillages);
        } else if (!verrouillages.isEmpty()) {
            applyVerrouillages(postes, animateurs, verrouillages, planPersiste);
        }
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningEvenement evenement = new PlanningEvenement(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
        evenement.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        evenement.setVerrouillages(verrouillages);
        return evenement;
    }

    /** How much of an incremental problem is frozen versus re-opened (issue #86). */
    public record StatistiquesIncremental(
            int postesTotal,
            int postesFiges,
            int postesLiberes,
            int postesLiberesManuellement,
            int postesNouveaux) {
    }

    /**
     * An incremental re-solve problem: the planning to hand to the solver, how
     * much of it is frozen, and the persisted assignments it was seeded from —
     * kept so the caller can diff the result against them.
     */
    public record ProblemeIncremental(PlanningEvenement planning, StatistiquesIncremental statistiques,
            Map<String, List<String>> affectationsPrecedentes) {
    }

    /**
     * A full-solve problem and where it started from (issue #174).
     *
     * @param planning         the problem to solve, seeded or not
     * @param reamorcage       what was actually done — never {@link Reamorcage#AUTO},
     *                         which is a request, not an outcome
     * @param postesReamorces  seats carrying an animateur from the persisted
     *                         plan and left <b>movable</b>; 0 on a cold start
     * @param postesLiberes    seats the persisted plan staffed but that had to
     *                         start empty: the animateur is gone, or has since
     *                         declared that day off
     */
    public record ProblemeReamorce(PlanningEvenement planning, Reamorcage reamorcage, int postesReamorces,
            int postesLiberes) {
    }

    /**
     * The problem of a full solve, started from the persisted plan when
     * {@code reamorcage} asks for it (issue #174): the same seats as
     * {@link #buildFromReferenceData()}, re-seeded positionally on
     * stand × créneau — the convention of the locks (#87) and of the
     * incremental re-solve (#86) — and <b>pinned nowhere</b> beyond the
     * explicit locks. That is the whole difference with the incremental
     * re-solve: this one re-optimises everything, it just does not throw away
     * what the previous solve had reached. Timefold scores the seed first and
     * keeps the best solution seen, so the result is never below it.
     *
     * <p>{@link Reamorcage#AUTO} re-seeds when a plan exists and starts cold
     * otherwise; {@link Reamorcage#PLAN_COURANT} fails on an edition without a
     * plan rather than quietly starting cold; {@link Reamorcage#AUCUN} is the
     * cold start, by name. The same building block serves a warm restart of
     * an interrupted job (#183) the day that exists: nothing here is specific
     * to the Solveur screen.</p>
     */
    public ProblemeReamorce buildFromReferenceData(Reamorcage reamorcage) {
        Reamorcage demande = reamorcage == null ? Reamorcage.AUTO : reamorcage;
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listSolvedStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        Map<String, List<String>> affectationsPrecedentes = demande == Reamorcage.AUCUN
                ? Map.of()
                : persistence.get().loadAnimateursByStandCreneau();
        if (demande == Reamorcage.PLAN_COURANT && affectationsPrecedentes.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun plan enregistré sur cette édition : rien d'où repartir. "
                            + "Lancez un calcul de zéro (reamorcage=AUCUN), ou laissez le choix automatique.");
        }
        recordStandFamilies(stands, creneaux);
        PlanningEvenement planning = buildFromReferenceData(animateurs, stands, creneaux, affectationsPrecedentes);
        if (affectationsPrecedentes.isEmpty()) {
            return new ProblemeReamorce(planning, Reamorcage.AUCUN, 0, 0);
        }
        int[] bilan = reamorcerDepuisAffectations(planning.getPostes(), animateurs, affectationsPrecedentes,
                planning.getContraintesAdHoc());
        return new ProblemeReamorce(planning, Reamorcage.PLAN_COURANT, bilan[0], bilan[1]);
    }

    /**
     * The warm start itself (issue #174): every seat still free after the
     * locks were applied gets the animateur the persisted plan gave it, and
     * stays movable. A seat the locks already pinned is left exactly as they
     * left it — the positional walk still counts it, so the seats after it
     * keep their tenant. A tenant the referential no longer knows, or who has
     * since declared the seat's day off, or whom a forced unavailability now
     * covers, leaves the seat empty: seeding a violation the solver would have
     * to undo first is a worse start than a hole, and it is the incremental
     * re-solve's own rule. Package-private and static so it can be unit-tested
     * without a database, like {@link #figerPostesIncremental}.
     *
     * @return {@code {seeded, freed}}
     */
    static int[] reamorcerDepuisAffectations(List<PosteAffectation> postes, List<Animateur> animateurs,
            Map<String, List<String>> animateursPersistes, List<ContrainteAdHoc> contraintesAdHoc) {
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        List<ContrainteAdHoc> indisponibilitesForcees = contraintesAdHoc == null
                ? List.of()
                : contraintesAdHoc.stream()
                        .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                        .toList();
        Map<String, Integer> prochainePlace = new HashMap<>();
        int reamorces = 0;
        int liberes = 0;
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = animateursPersistes.getOrDefault(key, List.of());
            int place = prochainePlace.merge(key, 1, Integer::sum) - 1;
            if (poste.isVerrouille() || place >= tenants.size()) {
                continue;
            }
            Animateur tenant = animateursById.get(tenants.get(place));
            poste.setAnimateur(tenant);
            if (tenant == null || indisponible(tenant, poste)
                    || forbiddenByContrainteAdHoc(indisponibilitesForcees, poste)) {
                poste.setAnimateur(null);
                liberes++;
                continue;
            }
            // Deliberately no setVerrouille(true): that line is what makes the
            // incremental re-solve incremental, and its absence is this method.
            reamorces++;
        }
        return new int[] {reamorces, liberes};
    }

    /**
     * Builds an incremental re-solve problem (issue #86): the same seats as
     * {@link #buildFromReferenceData()}, but seeded from the persisted
     * plan and <b>pinned wherever that plan is still valid</b>, so a short
     * solve only has to fill what a late change actually opened — a fresh
     * unavailability, a new stand, seats the previous solve left empty, plus
     * whatever {@code scope} re-opens on purpose.
     *
     * <p>Seats are matched positionally on stand × créneau, the same convention
     * as the locks of issue #87 (see
     * {@link PlanningPersistenceService#loadAnimateursByStandCreneau()}):
     * the seats of one stand and créneau are interchangeable, so no seat id has
     * to survive a reference-data change for the reconciliation to hold.</p>
     *
     * <p>Everything still valid and outside the perimeter is pinned, including
     * seats covered by no explicit lock: an incremental re-solve exists to keep
     * the standing plan stable, not to re-optimise it. Re-opening a validated
     * area is therefore an explicit act — name it in {@code scope}, or run
     * a full solve with locks protecting what must survive it.</p>
     */
    public ProblemeIncremental buildIncrementalFromReferenceData(ReplanificationScope scope) {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listSolvedStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        recordStandFamilies(stands, creneaux);
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        Map<String, List<String>> affectationsPrecedentes =
                persistence.get().loadAnimateursByStandCreneau();
        if (affectationsPrecedentes.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun plan persisté : lancez d'abord une résolution complète, "
                            + "la replanification incrémentale repart de son résultat.");
        }
        List<PosteAffectation> postes = buildPostes(stands, creneaux);
        List<ContrainteAdHoc> contraintesAdHoc = referenceDataService.snapshotContraintes();
        StatistiquesIncremental statistiques = figerPostesIncremental(postes, animateurs, affectationsPrecedentes,
                scope == null ? ReplanificationScope.automatic() : scope, contraintesAdHoc);
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningEvenement evenement = new PlanningEvenement(dateDebut, animateurs, postes, contraintesAdHoc);
        evenement.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        evenement.setVerrouillages(referenceDataService.listVerrouillages());
        return new ProblemeIncremental(evenement, statistiques, affectationsPrecedentes);
    }

    /**
     * The incremental reconciliation itself (issue #86), positional like
     * {@link #applyVerrouillages}: every seat is re-seeded with the
     * animateur the persisted plan gave it, then
     * <ul>
     * <li>named by {@code scope} → cleared and left free, whatever its
     * state: this is the operator saying "redo that";</li>
     * <li>still valid (the animateur exists and is not unavailable on the
     * seat's day) → pinned, the solver may not touch it;</li>
     * <li>invalidated by a late change (animateur deleted, freshly declared
     * unavailable that day, or covered by a fresh forced-unavailability ad hoc
     * constraint) → cleared and left free: exactly what the incremental solve
     * has to re-fill. Pinning such a seat would freeze a hard violation nobody
     * could then fix;</li>
     * <li>never staffed, or newly created (added stand or créneau) → left
     * free, as in a full solve.</li>
     * </ul>
     * Package-private and static so it can be unit-tested without a database.
     */
    static StatistiquesIncremental figerPostesIncremental(List<PosteAffectation> postes, List<Animateur> animateurs,
            Map<String, List<String>> animateursPersistes, ReplanificationScope scope,
            List<ContrainteAdHoc> contraintesAdHoc) {
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        // Filtered once: the loop below runs on thousands of seats, and this
        // list is normally empty.
        List<ContrainteAdHoc> indisponibilitesForcees = contraintesAdHoc == null
                ? List.of()
                : contraintesAdHoc.stream()
                        .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                        .toList();
        Map<String, Integer> prochainePlace = new HashMap<>();
        int figes = 0;
        int liberes = 0;
        int liberesManuellement = 0;
        int nouveaux = 0;
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = animateursPersistes.getOrDefault(key, List.of());
            int place = prochainePlace.merge(key, 1, Integer::sum) - 1;
            String tenantId = place < tenants.size() ? tenants.get(place) : null;
            if (tenantId == null) {
                nouveaux++;
                continue;
            }
            if (scope.release(poste, tenantId)) {
                liberesManuellement++;
                continue;
            }
            Animateur tenant = animateursById.get(tenantId);
            // Seeded first: the ad hoc check below reads the seat as staffed,
            // exactly like the constraint it shares its implementation with.
            poste.setAnimateur(tenant);
            if (tenant == null || indisponible(tenant, poste)
                    || forbiddenByContrainteAdHoc(indisponibilitesForcees, poste)) {
                poste.setAnimateur(null);
                liberes++;
                continue;
            }
            poste.setVerrouille(true);
            figes++;
        }
        return new StatistiquesIncremental(postes.size(), figes, liberes, liberesManuellement, nouveaux);
    }

    private static boolean indisponible(Animateur animateur, PosteAffectation poste) {
        return animateur.getJoursIndisponibles() != null
                && animateur.getJoursIndisponibles().contains(poste.getCreneau().getDate());
    }

    /**
     * Whether a forced-unavailability ad hoc constraint forbids this seat as
     * staffed — the other way a late change lands, alongside a day off. The
     * predicate is the solver's own
     * ({@link AdHocConstraints#violatesForcedIndisponibilite}), so the two can
     * never disagree about what is allowed.
     */
    private static boolean forbiddenByContrainteAdHoc(List<ContrainteAdHoc> indisponibilitesForcees,
            PosteAffectation poste) {
        for (ContrainteAdHoc contrainte : indisponibilitesForcees) {
            if (AdHocConstraints.violatesForcedIndisponibilite(contrainte, poste)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Freezes the seats covered by the active group's locks (issue #87): each
     * one is re-seeded with the animateur the last persisted solve gave it and
     * pinned ({@link PosteAffectation#setVerrouille}), so no move can change it
     * while the rest of the plan is re-optimised from scratch.
     *
     * <p>Only a seat that <em>was</em> staffed can be frozen: an empty seat is
     * left unassigned and movable, because pinning a hole would make it
     * permanently unfillable. For the same reason, a lock recorded before any
     * solve has been persisted simply freezes nothing.</p>
     *
     * <p>Seats are re-seeded before the locks are evaluated because a
     * {@link TypeVerrouillage#ANIMATEUR} lock is expressed in terms of who
     * holds the seat; anything seeded but not covered by a lock is cleared
     * again, leaving the unlocked part of the problem exactly as it was
     * before.</p>
     */
    private void applyVerrouillages(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages) {
        PlanningPersistenceService persistenceService = persistence.get();
        if (verrouillages.isEmpty() || persistenceService == null) {
            return;
        }
        applyVerrouillages(postes, animateurs, verrouillages,
                persistenceService.loadAnimateursByStandCreneau());
    }

    /**
     * The pinning itself, taking the persisted assignments as a parameter:
     * package-private and static so it can be unit-tested without a database,
     * like {@link #buildPostes}.
     */
    static void applyVerrouillages(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages, Map<String, List<String>> animateursPersistes) {
        if (verrouillages.isEmpty()) {
            return;
        }
        seedFromAffectations(postes, animateurs, verrouillages, animateursPersistes);
    }

    /**
     * Re-seeds the seats positionally from {@code seed} (animateur ids per
     * stand × créneau key, seat order — ids are interchangeable within one
     * key, see {@link PlanningPersistenceService#loadAnimateursByStandCreneau()}),
     * pins the seats covered by a lock, and clears the others again so the
     * solver restarts from scratch everywhere it is free to. An animateur id
     * the referential no longer knows simply leaves its seat empty — a stale
     * seed is a worse starting point, never an error.
     *
     * <p>The variant that <em>keeps</em> the unlocked seeds as a warm start
     * belongs to the incremental re-solve of issue #86, and lives in
     * {@link #figerPostesIncremental}: it has its own notion of what stays
     * valid, and pins rather than merely seeds.</p>
     */
    static void seedFromAffectations(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages, Map<String, List<String>> seed) {
        if (seed.isEmpty()) {
            return;
        }
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        Map<String, Integer> prochaineePlace = new HashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = seed.getOrDefault(key, List.of());
            int place = prochaineePlace.merge(key, 1, Integer::sum) - 1;
            if (place < tenants.size()) {
                poste.setAnimateur(animateursById.get(tenants.get(place)));
            }
            if (poste.getAnimateur() == null) {
                continue;
            }
            boolean gele = verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste));
            poste.setVerrouille(gele);
            if (!gele) {
                poste.setAnimateur(null);
            }
        }
    }

    /**
     * One {@link PosteAffectation} per required seat on every stand × timeslot
     * × open segment (see {@link Creneau#segmentsOuverts(Stand)}),
     * all seats unassigned. Package-private and static so it can be unit-tested
     * without a database.
     *
     * <p>The seat count comes from the <b>open segment</b>, not from the stand:
     * a stand whose staffing varies during the day states it per opening window
     * ({@link dev.sylvain.planning.domain.FenetreHoraire#getEffectif()}), and a
     * window that names none falls back to {@code stand.effectifMin}. A slot
     * spanning two windows of different effectifs therefore yields two groups of
     * seats, each carrying the effective time window of its own segment.</p>
     *
     * <p>The fallback is {@code effectifMin}, not {@code effectifMax}:
     * {@code effectifMax} is the upper capacity a stand could accept, not the
     * number of seats that must be staffed (that's exactly what
     * {@code posteDoitEtrePourvu} makes a hard requirement for every generated
     * poste). Confirmed against scenario-complet.yaml, whose hand-authored poste
     * list — since dropped as redundant with this very method — held 2088
     * entries, precisely {@code sum(effectifMin) * creneaux} (58 * 36); the
     * effectifMax sum instead gives 2736, 31% more mandatory seats than the
     * scenario intends. Building a problem from reference data with effectifMax
     * silently inflated every solve started from "Lancer le solveur" into a
     * substantially bigger, harder problem than the one actually staffed
     * for — the real reason it kept stalling short of hard-feasibility.</p>
     *
     * <p>Falling back to {@code effectifMin} on <i>every</i> slot was in turn
     * what made a real event's planning cover 7 155 h where its source workbook
     * needed 10 986: the minimum is what a stand needs at its quietest hour, and
     * applying it at the peak under-staffs by a third. Hence the per-window
     * effectif.</p>
     *
     * <p>A stand closed for only part of a créneau (see
     * {@link dev.sylvain.planning.domain.IndisponibiliteStand})
     * still generates a poste for the créneau's open remainder(s), each one
     * carrying an effective time-window override
     * ({@link PosteAffectation#getHeureDebutEffective()}) narrower than the
     * créneau itself — the poste still references the real, persisted créneau
     * (a hard requirement of {@code poste_affectation.creneau_id}'s foreign
     * key), so it cannot be split into a synthetic sub-créneau instead.</p>
     *
     * <p>When {@code creneaux} contains more than one relay-grid "famille"
     * (see {@link VacationGeneratorService#generateVacations}), each stand is
     * deterministically assigned to exactly one (see
     * {@link #spreadStandsByFamily}) and only ever paired against that
     * famille's créneaux, instead of the full cross product. Whichever family a
     * stand lands on is stable across regenerations (it depends only on the set
     * of stand ids), so re-running découpage doesn't reshuffle which stands
     * share a grid.
     * With a single famille (the default, {@code famille} always 0) this is
     * exactly the historical unfiltered cross product.</p>
     */
    public static List<PosteAffectation> buildPostes(List<Stand> stands, List<Creneau> creneaux) {
        int nombreFamilles = creneaux.stream().mapToInt(Creneau::getFamille).max().orElse(0) + 1;
        Map<String, Integer> familleParStand = spreadStandsByFamily(stands, nombreFamilles);
        List<PosteAffectation> postes = new ArrayList<>();
        int counter = 0;
        for (Stand stand : stands) {
            int familleStand = familleParStand.get(stand.getId());
            for (Creneau creneau : creneaux) {
                if (creneau.getFamille() != familleStand) {
                    continue;
                }
                List<Creneau.SegmentOuvert> segments = creneau.segmentsOuverts(stand);
                boolean creneauEntierOuvert = segments.size() == 1 && segments.get(0).debutMinutes() == 0
                        && segments.get(0).finMinutes() == creneau.getDureeMinutes();
                for (Creneau.SegmentOuvert segment : segments) {
                    // At least one seat on an open stand, half on a
                    // break-covering shift: the rule lives on the slot so the
                    // analyses count exactly what is generated here.
                    int seats = creneau.siegesSegment(segment.effectif());
                    for (int seat = 0; seat < seats; seat++) {
                        PosteAffectation poste = new PosteAffectation("poste-" + (counter++), stand, creneau);
                        if (!creneauEntierOuvert) {
                            poste.setHeureDebutEffective(shift(creneau.getHeureDebut(), segment.debutMinutes()));
                            poste.setHeureFinEffective(shift(creneau.getHeureDebut(), segment.finMinutes()));
                        }
                        postes.add(poste);
                    }
                }
            }
        }
        return postes;
    }

    /**
     * Assigns every stand to exactly one relay-grid famille, round-robin over
     * the stands sorted by id. Deterministic and stable across regenerations
     * (it depends on nothing but the set of stand ids), just like the hash it
     * replaces — but <b>balanced</b>, which the hash was not.
     *
     * <p>{@code floorMod(id.hashCode(), n)} spreads ids pseudo-randomly, and on
     * a roster this small that is visibly lumpy: on the reference scenario it
     * put 36 of the 91 seats on a single famille out of four (19/21/36/15).
     * That famille alone then changed crew at one instant with 40% of the whole
     * event's demand behind it, which is exactly the simultaneity peak the
     * staggering exists to break — the mechanism was working against itself.
     * Round-robin over sorted ids gives buckets that differ by at most one
     * stand.</p>
     */
    /**
     * Which stagger family each stand belongs to, for the given grid — the
     * pairing {@link #buildPostes} applies. Exposed because every screen that
     * shows a stand against a créneau has to agree with it: a stand only ever
     * receives seats on its own family's créneaux, so a grid showing all of
     * them invites an entry that generates nothing.
     */
    public static Map<String, Integer> standFamilies(List<Stand> stands, List<Creneau> creneaux) {
        int nombreFamilles = creneaux.stream().mapToInt(Creneau::getFamille).max().orElse(0) + 1;
        return spreadStandsByFamily(stands, nombreFamilles);
    }

    /**
     * A stand that already has a family keeps it (issue #390); the others,
     * in id order, each join the least populated family, lowest first. On
     * stands that have none this is exactly the historical round-robin, so
     * an edition that never persisted anything is spread as before — and a
     * stand added later joins without moving anybody.
     */
    /**
     * Writes down the families this solve pairs the stands on (issue #390), so
     * every later build keeps them.
     *
     * <p>Only from a build that <b>solves</b>: the same builder serves
     * {@code GET /api/planning/volumetrie}, {@code GET /api/staffing} and two
     * read-only MCP tools, and a read has no business writing. And only on a
     * staggered grid: on a single-family one the assignment says nothing —
     * freezing every stand at family 0 would starve the other families the day
     * a staggered grid arrives without replacing this one.</p>
     */
    private void recordStandFamilies(List<Stand> stands, List<Creneau> creneaux) {
        int nombreFamilles = creneaux.stream().mapToInt(Creneau::getFamille).max().orElse(0) + 1;
        if (nombreFamilles <= 1) {
            return;
        }
        referenceDataService.recordStandFamilies(stands, standFamilies(stands, creneaux));
    }

    private static Map<String, Integer> spreadStandsByFamily(List<Stand> stands, int nombreFamilles) {
        Map<String, Integer> families = new HashMap<>();
        int[] population = new int[nombreFamilles];
        List<Stand> sansFamille = new ArrayList<>();
        for (Stand stand : stands.stream().sorted(Comparator.comparing(Stand::getId)).toList()) {
            Integer famille = stand.getFamille();
            if (famille != null && famille >= 0 && famille < nombreFamilles) {
                families.put(stand.getId(), famille);
                population[famille]++;
            } else {
                sansFamille.add(stand);
            }
        }
        for (Stand stand : sansFamille) {
            int moinsPeuplee = 0;
            for (int famille = 1; famille < nombreFamilles; famille++) {
                if (population[famille] < population[moinsPeuplee]) {
                    moinsPeuplee = famille;
                }
            }
            families.put(stand.getId(), moinsPeuplee);
            population[moinsPeuplee]++;
        }
        return families;
    }

    /** {@code heureDebut} shifted forward by {@code minutes}, wrapping past midnight. */
    public static LocalTime shift(LocalTime heureDebut, int minutes) {
        return LocalTime.ofSecondOfDay(Math.floorMod(heureDebut.toSecondOfDay() + minutes * 60L, 24 * 3600L));
    }
}
