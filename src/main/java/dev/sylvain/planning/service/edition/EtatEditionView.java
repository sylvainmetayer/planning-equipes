package dev.sylvain.planning.service.edition;

import java.time.Instant;
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
        EtatCollecte collecte,
        EtatOuvertures ouvertures,
        EtatBesoin besoin,
        EtatResolution resolution,
        EtatProblemes problemes,
        EtatPublication publication,
        EtatConfirmations confirmations,
        EtatFoire foire) {

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
     */
    @Schema(requiredProperties = {"anomalies", "fenetresSansEffet", "standsJamaisOuverts", "statut"})
    public record EtatOuvertures(int anomalies, int fenetresSansEffet, int standsJamaisOuverts, Statut statut) {}

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
}
