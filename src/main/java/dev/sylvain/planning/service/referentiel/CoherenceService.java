package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

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
     * Warnings about a hand-entered exception just written: a forced assignment
     * that falls only on days its animateurs declared off. The stands' recurring
     * rules are resolved on the edition's grid first, so a stand-scoped exception
     * reads the days that stand really opens.
     */
    public List<Avertissement> onContrainteAdHoc(ContrainteAdHoc contrainte) {
        List<Creneau> edition = creneaux.list();
        List<Stand> resolus = stands.list();
        HoraireStandResolver.apply(resolus, edition);
        return CoherenceAnalyzer.onContrainteAdHoc(contrainte, animateurs.list(), resolus, edition);
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
