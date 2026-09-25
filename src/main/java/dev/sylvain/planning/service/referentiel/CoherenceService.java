package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;

/**
 * Feeds {@link CoherenceAnalyzer} the referential it needs, and nothing else:
 * every rule stays in the pure analyzer, this bean only reads.
 *
 * <p>Adds no SQL of its own — it goes through {@link CreneauService} and
 * {@link StandService}, which already carry the edition predicate.</p>
 *
 * <p><b>Reached from MCP too, since #449</b>, through the {@code write*} of
 * {@link ReferenceDataService} that every writing tool now calls. What the
 * assistant reads is decided by {@code mcp/WarningCodes}: the <em>type</em>
 * of each warning, never its sentence — a message on an animateur dates
 * their majority, that is their birth date shifted by eighteen years, the
 * field the MCP views withhold (issue #107). The messages themselves name an
 * animateur by their id only, and have no business in the browser's
 * notification log either ({@code docs/rgpd.md} §7). A new warning is written
 * with that in mind: the code is the contract, the sentence is for the
 * screen.</p>
 */
@ApplicationScoped
public class CoherenceService {

    private final CreneauService creneaux;

    private final StandService stands;

    private final AnimateurService animateurs;

    private final VerrouillageService verrouillages;

    /**
     * Read for one thing only: which seats of the persisted plan the animateurs
     * of an exception already hold. {@code Instance} rather than a plain
     * injection because this bean is also built by hand in the plain-Java
     * harnesses, which have no database — the lock check is then simply not
     * run, never guessed at.
     */
    private final Instance<PlanningPersistenceService> plan;

    /**
     * The latest score analysis, read for the lock warning alone and behind the
     * same indirection as {@link #plan}, for the same reason.
     */
    private final Instance<ConstraintAnalysisStore> analyses;

    /**
     * The moment the past is judged against (ADR 0044), behind the same
     * indirection: a rule left on a day already worked is history, and the
     * warnings say nothing about it.
     */
    private final Instance<PlanningService> planning;

    @Inject
    public CoherenceService(
            CreneauService creneaux,
            StandService stands,
            AnimateurService animateurs,
            VerrouillageService verrouillages,
            Instance<PlanningPersistenceService> plan,
            Instance<ConstraintAnalysisStore> analyses,
            Instance<PlanningService> planning) {
        this.creneaux = creneaux;
        this.stands = stands;
        this.animateurs = animateurs;
        this.verrouillages = verrouillages;
        this.plan = plan;
        this.analyses = analyses;
        this.planning = planning;
    }

    /** The event's span, derived from the créneaux — an {@code Edition} stores none. */
    public JoursEvenement joursEvenement() {
        return JoursEvenement.of(creneaux.list());
    }

    /** Warnings about a fiche being <b>created</b>: everything it says is new. */
    public List<Avertissement> onAnimateur(Animateur animateur) {
        return CoherenceAnalyzer.onAnimateur(null, animateur, joursEvenement());
    }

    /**
     * Warnings about an <b>edit</b>, read against the fiche as it stood before
     * it. {@code avant} is what keeps a bulk edit from re-raising what nobody
     * touched — see {@link CoherenceAnalyzer#onAnimateur(Animateur, Animateur,
     * JoursEvenement)}.
     */
    public List<Avertissement> onAnimateur(Animateur avant, Animateur apres) {
        return CoherenceAnalyzer.onAnimateur(avant, apres, joursEvenement());
    }

    /**
     * Warnings about one timeslot, read against the stands as the solver will
     * see them.
     *
     * <p>The recurring rules are expanded <b>against this timeslot</b> rather
     * than through {@link StandService#listSolved()}: that view resolves the
     * days the <em>persisted</em> grid covers, so a timeslot being created on a
     * brand new date would be compared against stands whose rules had not been
     * expanded for it — every rule-scheduled stand would look shut, and the
     * warning would fire on a perfectly good timeslot.</p>
     */
    public List<Avertissement> onCreneau(Creneau creneau) {
        List<Stand> resolus = stands.list();
        if (creneau != null) {
            HoraireStandResolver.apply(resolus, List.of(creneau));
        }
        return CoherenceAnalyzer.onCreneau(creneau, resolus);
    }

    /**
     * The edition's stands with their own hours expanded on {@code creneaux} —
     * rules, then dated exceptions, and no consigne: what every warning of this
     * bean reads a stand against. The coherence checklist replays those
     * warnings on the whole edition and resolves its stands here, so that a
     * timeslot inside a consigne band is not reported outside every opening.
     */
    public List<Stand> standsOnOwnHours(List<Creneau> creneaux) {
        List<Stand> resolus = stands.list();
        HoraireStandResolver.apply(resolus, creneaux);
        return resolus;
    }

    /**
     * Warnings about a hand-entered exception just written: the three readings
     * of « this forced assignment cannot be honoured » that
     * {@link CoherenceAnalyzer#onContrainteAdHoc} collects. The stands'
     * recurring rules are resolved on the edition's grid first, so a
     * stand-scoped exception reads the days that stand really opens.
     *
     * <p>The locks are read here too, and the seats of the persisted plan with
     * them when there is any lock to cross: an exception naming somebody whose
     * schedule is frozen over its whole scope is unsatisfiable. With no lock
     * recorded — the usual case — the assignments are not read at all, and the
     * cost is one read of an empty table.</p>
     */
    public List<Avertissement> onContrainteAdHoc(ContrainteAdHoc contrainte) {
        List<Creneau> edition = creneaux.list();
        List<Stand> resolus = stands.list();
        HoraireStandResolver.apply(resolus, edition);
        List<VerrouillagePlanning> verrous = verrouillages.list();
        // The seats of the plan are a full scan of the assignments, and only
        // the lock check reads them: with no lock recorded — the usual case —
        // nothing needs them.
        Set<ForcedAssignmentOnLockedSchedule.PlaceTenue> tenues = verrous.isEmpty() || !plan.isResolvable()
                ? Set.of()
                : plan.get().loadPlacesTenues();
        return CoherenceAnalyzer.onContrainteAdHoc(
                contrainte,
                animateurs.list(),
                resolus,
                edition,
                verrous,
                tenues,
                planning.isResolvable() ? planning.get().pastHorizon() : null);
    }

    /**
     * Warnings about the lock just posted: the seats it freezes already carry a
     * hard violation in the latest analysis — see
     * {@link CoherenceAnalyzer#onVerrouillage}.
     *
     * <p>The analysis is the one the store already holds, never a solve: on an
     * edition nobody has solved there is nothing to read, and this warning has
     * nothing to say rather than a run to launch. An empty store does derive
     * the analysis from the persisted plan — one score calculation, paid once
     * per edition and per restart, as every reader of that store pays it.</p>
     */
    public List<Avertissement> onVerrouillage(VerrouillagePlanning verrouillage) {
        if (!analyses.isResolvable()) {
            return List.of();
        }
        ConstraintAnalysisStore.StoredAnalysis stockee = analyses.get().latest();
        if (stockee == null || stockee.diagnostic() == null) {
            return List.of();
        }
        return CoherenceAnalyzer.onVerrouillage(
                verrouillage, stockee.diagnostic().contraintes(), creneaux.list());
    }

    /**
     * Warnings about one stand, read against every créneau of the edition —
     * the stand's rules resolved for them first, on the written instance, whose
     * resolved view is cleared again afterwards so the response carries the
     * rules and exceptions alone. {@code avant} is {@code null} for a creation.
     */
    public List<Avertissement> onStand(Stand avant, Stand apres) {
        if (apres == null) {
            return List.of();
        }
        List<Creneau> edition = creneaux.list();
        HoraireStandResolver.apply(List.of(apres), edition);
        try {
            return CoherenceAnalyzer.onStand(avant, apres, edition);
        } finally {
            apres.setFenetresEffectives(null, null);
        }
    }
}
