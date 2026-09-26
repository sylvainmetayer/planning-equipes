package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.service.analyse.ScoreReading;
import dev.sylvain.planning.service.referentiel.GelReferentielService;
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
 *
 */
public record EtatEditionView(
        String editionId,
        String editionNom,
        EtatReferentiels referentiels,
        EtatGelReferentiel gel,
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
        EtatATraiter aTraiter,
        EtatEvenement evenement) {

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

    /**
     * Where the edition stands against its own dates, which decides the form
     * of the home screen: the checklist before, the day during, the archive
     * after.
     */
    public enum Phase {
        /** Before the first day, and always on an edition without a timeslot. */
        PREPARATION,
        /** From the first day to the last, both included. */
        EVENEMENT,
        /** The last day is behind today. */
        APRES
    }

    /**
     * The event's bounds, derived from the timeslots and never stored — the
     * first and last day carrying one, {@code null} both when there is none,
     * for an edition without a timeslot has no bounds.
     *
     * @param termine    the last day is behind the day {@code JourJClock} says it
     *                   is: the screen then offers to archive the edition. Never
     *                   true without bounds
     * @param aujourdhui the day {@code JourJClock} says it is, the simulated one
     *                   when the clock is frozen
     * @param phase      before, during or after the event, judged on {@code aujourdhui}
     * @param jour       the day under way, only while the event runs — {@code null}
     *                   otherwise
     */
    @Schema(requiredProperties = {"aujourdhui", "phase", "termine"})
    public record EtatEvenement(
            LocalDate premierJour,
            LocalDate dernierJour,
            boolean termine,
            LocalDate aujourdhui,
            Phase phase,
            EtatJour jour) {}

    /**
     * The day under way, in the figures the home screen's first line reads
     * during the event. Counted by the services that already count them — the
     * wall display for the stands and the seats, the mode jour J for the
     * absences — never a fourth time here.
     *
     * @param date              the journée under way: the evening that opened a
     *                          shift still running after midnight, until it ends
     * @param numero            its rank from the first day of the event, the first
     *                          day being 1 (« J5 »)
     * @param standsOuverts     stands holding at least one seat that day
     * @param placesVides       seats of that day nobody holds
     * @param absents           animateurs marked absent on one of its timeslots
     * @param echangesAArbitrer swap requests waiting for the organisation's decision
     */
    @Schema(requiredProperties = {"absents", "date", "echangesAArbitrer", "numero", "placesVides", "standsOuverts"})
    public record EtatJour(
            LocalDate date, int numero, int standsOuverts, int placesVides, int absents, int echangesAArbitrer) {}

    /**
     * How many rows each referential holds; a zero anywhere is a step still to do.
     *
     * @param typologiesOrphelines game categories a stand proposes and nobody
     *                             holds, polyvalents aside — enough to make the
     *                             step « à vérifier », since only a ninja can
     *                             then take those stands
     */
    @Schema(requiredProperties = {"animateurs", "creneaux", "stands", "statut", "typologiesOrphelines"})
    public record EtatReferentiels(int stands, int animateurs, int creneaux, int typologiesOrphelines, Statut statut) {}

    /**
     * The freeze of the referential (ADR 0052), family by family: what the
     * organiser declared ready and no path may write any more. Read beside the
     * referentials line, since a frozen family is a preparation step closed.
     * {@code FAIT} once the stands and the timeslots are frozen — the two the
     * screens propose to freeze after a first solve —, {@code INFO} otherwise:
     * freezing is offered, never required, and an open family holds nothing
     * back.
     *
     * @param familles the four families, frozen or not, in declaration order
     */
    @Schema(requiredProperties = {"familles", "statut"})
    public record EtatGelReferentiel(List<GelReferentielService.EtatGel> familles, Statut statut) {}

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
     * @param lecture          the last analysis read out in sentences (« Lecture du
     *                         score »), what the home screen shows in place of the
     *                         raw score; empty without an analysis
     */
    @Schema(requiredProperties = {"dataStale", "lecture", "resolue", "solveEnCours", "statut"})
    public record EtatResolution(
            boolean resolue,
            Instant resoluLe,
            String score,
            String scoreHorsPlancher,
            Boolean faisable,
            boolean dataStale,
            boolean solveEnCours,
            Statut statut,
            List<ScoreReading.ScoreSentence> lecture) {}

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

    /**
     * The acknowledgements of the published plan, over the people it seats.
     *
     * @param relancesAutomatiques the nightly sends are armed on this edition
     *                             (Paramètres › E-mails) — off by default, and
     *                             the reminder of the silent is one of them
     * @param delaiRelanceHeures   how long a silence lasts before it becomes a
     *                             reminder: the line is only « à vérifier » past it
     */
    @Schema(
            requiredProperties = {
                "confirmes",
                "delaiRelanceHeures",
                "relances",
                "relancesAutomatiques",
                "silencieux",
                "statut"
            })
    public record EtatConfirmations(
            int confirmes,
            int relances,
            int silencieux,
            Statut statut,
            boolean relancesAutomatiques,
            int delaiRelanceHeures) {}

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
     * @param rappelsNonEnvoyes        day-before reminders the nightly job could not send — a fiche
     *                                 without an address — for a day still ahead, and whose fiche
     *                                 still has none: the person has to be told by hand
     * @param relancesNonEnvoyees      reminders of the silent that could not leave since the last
     *                                 publication — the night's or a manual one, for want of an
     *                                 address or a send that failed — about somebody still silent
     * @param sauvegardeEnEchec        the last nightly backup failed
     * @param sauvegardeEchecLe        when that attempt ran, {@code null} without a failure
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
                "rappelsNonEnvoyes",
                "relancesNonEnvoyees",
                "sauvegardeEnEchec",
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
            int personnesAPrevenir,
            int rappelsNonEnvoyes,
            int relancesNonEnvoyees,
            boolean sauvegardeEnEchec,
            Instant sauvegardeEchecLe) {}
}
