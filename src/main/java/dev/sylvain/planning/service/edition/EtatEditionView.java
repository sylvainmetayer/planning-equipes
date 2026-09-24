package dev.sylvain.planning.service.edition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The checklist of an edition's cycle, one block per step of the « Prise en
 * main » guide, each carrying its state and the figures that decide it.
 * Served by {@code GET /api/editions/courant/etat} and by the MCP tool
 * {@code etat_edition}, so the home screen and an assistant read the same
 * aggregation.
 *
 * <p>Deliberately free of any person: counts, dates and scores only. The
 * screen links to the pages that hold the names; an assistant reads them
 * through the tools that anonymise them.</p>
 *
 * <p>Every block carries one of the {@link Statut} states, and the rule deciding each one
 * lives in {@link EtatEditionService#assemble} so it is tested without a
 * container. {@code null} on a nullable field means "not known", never
 * "zero": a score is {@code null} when no analysis of the persisted plan
 * exists in memory, which is what a restart leaves behind.</p>
 */
public record EtatEditionView(
        String editionId,
        String editionNom,
        EtatReferentiels referentiels,
        EtatCoherence coherence,
        EtatCollecte collecte,
        EtatOuvertures ouvertures,
        EtatBesoin besoin,
        EtatResolution resolution,
        EtatProblemes problemes,
        EtatRelecture relecture,
        EtatPublication publication,
        EtatConfirmations confirmations,
        EtatFoire foire,
        EtatATraiter aTraiter) {

    /**
     * The states a line of the checklist can be in, from the step still ahead
     * to the step behind. {@code INFO} sits between {@code ATTENTION} and
     * {@code FAIT}: there are figures worth reading, but none of them holds
     * the cycle back, and the screen says so in its own colour rather than in
     * the one it uses for a refusal.
     */
    public enum Statut {
        A_FAIRE,
        ATTENTION,
        INFO,
        FAIT
    }

    /** How many rows each referential holds; a zero anywhere is a step still to do. */
    @Schema(requiredProperties = {"animateurs", "creneaux", "stands", "statut"})
    public record EtatReferentiels(int stands, int animateurs, int creneaux, Statut statut) {}

    /**
     * The coherence checklist of the referential, counted by severity: every
     * anomaly the application detects on what was entered, read on the whole
     * edition — the detail is {@code GET /api/editions/courant/coherence}.
     *
     * @param bloquants    lines that guarantee a failed solve (contradictory exceptions, a malformed grid)
     * @param aVerifier    write-time warnings replayed, opening anomalies, the staffing bound
     * @param informations what is said for information only — a minor, rules that overlap
     */
    @Schema(requiredProperties = {"aVerifier", "bloquants", "informations", "statut"})
    public record EtatCoherence(int bloquants, int aVerifier, int informations, Statut statut) {}

    /**
     * @param ouverte               the collection window as configured
     * @param declarationsEnAttente declarations nobody has applied or refused yet
     * @param declarationsTraitees  declarations already applied or refused
     */
    @Schema(requiredProperties = {"declarationsEnAttente", "declarationsTraitees", "ouverte", "statut"})
    public record EtatCollecte(boolean ouverte, int declarationsEnAttente, int declarationsTraitees, Statut statut) {}

    /**
     * What the opening-hours analysis found on the stands the solver would read.
     *
     * @param fenetresSansEffet among the anomalies, the windows that overlap no créneau of their date —
     *                          a stand said open at an hour the grid does not have, which produces
     *                          nothing and is worth its own sentence on the home page
     * @param informations      among the anomalies, those reported for information only — rules or
     *                          windows of a stand that overlap, a rule no day reads: they alone never
     *                          make the line « à vérifier »
     */
    @Schema(requiredProperties = {"anomalies", "fenetresSansEffet", "informations", "standsJamaisOuverts", "statut"})
    public record EtatOuvertures(
            int anomalies, int fenetresSansEffet, int standsJamaisOuverts, int informations, Statut statut) {}

    /**
     * @param animateurs the roster as entered
     * @param minimum    the bound the staffing analysis retains
     * @param manque     {@code minimum - animateurs}, zero when the roster is large enough
     */
    @Schema(requiredProperties = {"animateurs", "manque", "minimum", "statut"})
    public record EtatBesoin(int animateurs, int minimum, int manque, Statut statut) {}

    /**
     * @param resolue          a plan is persisted for this edition
     * @param resoluLe         when it was solved
     * @param score            the score of the last analysed solve, {@code null} without an analysis
     * @param scoreHorsPlancher the same score net of its floors (issue #495)
     * @param faisable         hard score at zero on the last analysis; {@code null} without one
     * @param dataStale        reference data changed after the solve — computed here, not by the screen
     * @param solveEnCours     a solve holds this edition right now
     */
    @Schema(requiredProperties = {"dataStale", "resolue", "solveEnCours", "statut"})
    public record EtatResolution(
            boolean resolue,
            Instant resoluLe,
            String score,
            String scoreHorsPlancher,
            Boolean faisable,
            boolean dataStale,
            boolean solveEnCours,
            Statut statut) {}

    /**
     * @param bloquants        critical feasibility causes plus hard rules in default
     * @param avertissements   the other feasibility causes plus medium rules in default
     * @param reglesAnalysees  false when no rule analysis is in memory — the
     *                         store is per-process, so after a restart nothing
     *                         has measured the rules until a solve or a visit
     *                         to Contraintes. « Aucun problème signalé » would
     *                         then be an acknowledgement of a measurement that
     *                         never happened; the capacity causes, recomputed
     *                         on every call, are counted either way.
     */
    @Schema(requiredProperties = {"avertissements", "bloquants", "reglesAnalysees", "statut"})
    public record EtatProblemes(int bloquants, int avertissements, boolean reglesAnalysees, Statut statut) {}

    /**
     * Where the « relu et accepté » of the edition has got to — the step between
     * a plan that holds and a plan somebody has actually read.
     *
     * @param journees         days the timeslots span; zero before the grid exists
     * @param journeesValidees how many of them are accepted
     */
    @Schema(requiredProperties = {"journees", "journeesValidees", "statut"})
    public record EtatRelecture(int journees, int journeesValidees, Statut statut) {}

    /**
     * @param personnesAPrevenir people whose schedule the next publication would announce
     */
    @Schema(requiredProperties = {"jamaisPublie", "personnesAPrevenir", "statut"})
    public record EtatPublication(
            boolean jamaisPublie, Instant dernierePublicationLe, int personnesAPrevenir, Statut statut) {}

    /** The acknowledgements of the published plan, over the people it seats. */
    @Schema(requiredProperties = {"confirmes", "relances", "silencieux", "statut"})
    public record EtatConfirmations(int confirmes, int relances, int silencieux, Statut statut) {}

    /**
     * @param ouverte           the switch, as the admin set it
     * @param demandesEnAttente swap requests waiting for a colleague or for an admin decision
     */
    @Schema(requiredProperties = {"demandesEnAttente", "ouverte", "statut"})
    public record EtatFoire(boolean ouverte, int demandesEnAttente, Statut statut) {}

    /**
     * « À traiter aujourd'hui »: what waits on a decision of the organiser this
     * morning, read against the day {@code JourJClock} says it is. Counts and
     * dates only — the names are on the screens each subject links to. Nothing
     * is stored: a subject disappears because the data behind it changed, never
     * because somebody ticked it off.
     *
     * @param aujourdhui               the day the block was judged on, the simulated one when the
     *                                 recette clock is frozen
     * @param declarationsEnAttente    availability declarations neither applied nor refused,
     *                                 whether the collection is still open or not
     * @param plusAncienneDeclaration  when the oldest of them was submitted
     * @param echangesAArbitrer        swap requests waiting for the organisation's decision — the
     *                                 colleague has agreed; those still waiting on the colleague are not
     *                                 the organiser's to decide
     * @param echangesEnAlerte         among them, those waiting longer than
     *                                 {@code seuilAncienneteJours}, the notification setting
     * @param plusAncienEchange        since when the oldest of them has been waiting
     * @param journeesNonRelues        days of the edition among the {@code horizonJours} days starting
     *                                 today (today included),
     *                                 not yet accepted, once there is a plan to read — none after
     *                                 the event
     * @param silencieuxARelancer      people seated by the published plan who never answered, were
     *                                 not reminded since, and were told more than {@code silenceJours}
     *                                 days ago
     * @param donneesModifiees         the referential moved since the last solve — never while a
     *                                 solve runs, it will read the new data
     * @param personnesAPrevenir       what the next publication would announce, after a first one
     *                                 and outside a running solve
     */
    @Schema(
            requiredProperties = {
                "aujourdhui",
                "declarationsEnAttente",
                "donneesModifiees",
                "echangesAArbitrer",
                "echangesEnAlerte",
                "horizonJours",
                "journeesNonRelues",
                "personnesAPrevenir",
                "seuilAncienneteJours",
                "silenceJours",
                "silencieuxARelancer"
            })
    public record EtatATraiter(
            LocalDate aujourdhui,
            int declarationsEnAttente,
            Instant plusAncienneDeclaration,
            int echangesAArbitrer,
            int echangesEnAlerte,
            int seuilAncienneteJours,
            Instant plusAncienEchange,
            int horizonJours,
            List<LocalDate> journeesNonRelues,
            int silencieuxARelancer,
            int silenceJours,
            boolean donneesModifiees,
            int personnesAPrevenir) {}
}
