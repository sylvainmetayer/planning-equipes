package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Feeds {@link CoherenceAnalyzer} the referential it needs, and nothing else:
 * every rule stays in the pure analyzer, this bean only reads.
 *
 * <p>Adds no SQL of its own — it goes through {@link CreneauService} and
 * {@link StandService}, which already carry the edition predicate.</p>
 *
 * <p><b>Not reachable from MCP.</b> The MCP tools keep calling the plain
 * {@code create}/{@code update} of {@link ReferenceDataService}, which returns
 * the entity alone. The messages themselves name an animateur by their id
 * only — nom/prénom/dateNaissance never leave over that transport (issue #107),
 * and they have no business in the browser's notification log either
 * ({@code docs/rgpd.md} §7) — but wiring these warnings to a transport that has
 * no operator to read them is a separate decision, not made here.</p>
 */
@ApplicationScoped
public class CoherenceService {

    @Inject
    CreneauService creneaux;

    @Inject
    StandService stands;

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
}
