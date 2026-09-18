package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
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

    @Inject
    CreneauService creneaux;

    @Inject
    StandService stands;

    @Inject
    AnimateurService animateurs;

    @Inject
    VerrouillageService verrouillages;

    /**
     * Read for one thing only: which seats of the persisted plan the animateurs
     * of an exception already hold. {@code Instance} rather than a plain
     * injection because this bean is also built by hand in the plain-Java
     * harnesses, which have no database — the lock check is then simply not
     * run, never guessed at.
     */
    @Inject
    Instance<PlanningPersistenceService> plan;

    /**
     * The latest score analysis, read for the lock warning alone and behind the
     * same indirection as {@link #plan}, for the same reason.
     */
    @Inject
    Instance<ConstraintAnalysisStore> analyses;

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
     * Warnings about a hand-entered exception just written: the three readings
     * of « this forced assignment cannot be honoured » that
     * {@link CoherenceAnalyzer#onContrainteAdHoc} collects. The stands'
     * recurring rules are resolved on the edition's grid first, so a
     * stand-scoped exception reads the days that stand really opens.
     *
     * <p>The locks and the seats of the persisted plan are read here too, and
     * they are what the lock check needs: an exception naming somebody whose
     * schedule is frozen over its whole scope is unsatisfiable, and saying so
     * costs one read of the locks and one of the assignments — the same order
     * as the créneaux and stands this method already reads.</p>
     */
    public List<Avertissement> onContrainteAdHoc(ContrainteAdHoc contrainte) {
        List<Creneau> edition = creneaux.list();
        List<Stand> resolus = stands.list();
        HoraireStandResolver.apply(resolus, edition);
        return CoherenceAnalyzer.onContrainteAdHoc(
                contrainte,
                animateurs.list(),
                resolus,
                edition,
                verrouillages.list(),
                plan.isResolvable() ? plan.get().loadPlacesTenues() : Set.of());
    }

    /**
     * Warnings about the lock just posted: the seats it freezes already carry a
     * hard violation in the latest analysis — see
     * {@link CoherenceAnalyzer#onVerrouillage}.
     *
     * <p>The analysis is the stored one, asked for and never forced: on an
     * edition nobody has solved there is nothing to read, and this warning has
     * nothing to say rather than a solve to run.</p>
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
